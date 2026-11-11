/*
 * Contact form state machine for contact.html.
 *
 * Five states, an append-only localStorage audit trail of every transition,
 * rollback to the pre-submission snapshot on error, and a failover UI that
 * renders per-field validation messages while the user's input survives.
 *
 * The file is inert when loaded by a page that has no #contact-form.
 */
(function (global) {
    'use strict';

    const CONTACT_STATES = ["idle", "validating", "submitted", "success", "error"];
    const CONTACT_TRANSITIONS = {"idle":["validating"],"validating":["submitted","error"],"submitted":["success","error"],"success":["idle"],"error":["validating"]};
    const CONTACT_AUDIT_KEY = "utiltools.contact.audit.v1";
    const CONTACT_AUDIT_FIELDS = ["timestamp", "fromState", "toState", "formData"];
    const CONTACT_AUDIT_MAX = 50;

    const CONTACT_FIELD_LIMITS = {"name": 100, "email": 254, "message": 2000};
    const CONTACT_FIELD_IDS = ["name", "email", "message"];
    const CONTACT_EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

    /* Legal event -> target state, keyed by the state it applies to. An event
     * that has no row for the current state is ignored and audits nothing. */
    const CONTACT_EVENT_TARGETS = {
        "submit": {"idle": "validating", "error": "validating"},
        "validation-pass": {"validating": "submitted"},
        "validation-fail": {"validating": "error"},
        "dispatch-resolved": {"submitted": "success"},
        "dispatch-rejected": {"submitted": "error"},
        "reset": {"success": "idle"}
    };

    /* Network seam: the real POST to the audited submission endpoint with
     * bounded transient-failure failover. The factory returns the one
     * long-lived transport Promise: it resolves with the parsed success
     * envelope and rejects only on a terminal condition or attempt
     * exhaustion, tagged with `{ kind: 'terminal-4xx' | 'exhausted' }`.
     * `fetch` and `sleep` are injectable so the Node test driver can
     * substitute a fake fetch and record delays. */
    const SUBMISSIONS_PATH = '/api/v1/utils/contact/submissions';
    const MAX_ATTEMPTS = 3;
    const RETRY_AFTER_CAP_MS = 30000;
    const EXP_BASE_MS = 500;
    const EXP_CAP_MS = 8000;

    function isTransientStatus(status) {
        return status === 408 || status === 429 || status >= 500;
    }

    /* A bounded numeric Retry-After (seconds) wins over the exponential;
     * anything non-numeric or out of range falls back to exponential. */
    function retryAfterMs(response) {
        try {
            const value = Number(response.headers.get('Retry-After'));
            if (Number.isFinite(value) && value >= 0) {
                return Math.min(value * 1000, RETRY_AFTER_CAP_MS);
            }
        } catch (error) {
            /* headers not available: exponential */
        }
        return null;
    }

    /* Capped exponential: 500 ms * 2^(n-1), capped at 8 s. */
    function backoffMs(attemptIndex) {
        return Math.min(EXP_BASE_MS * Math.pow(2, attemptIndex - 1), EXP_CAP_MS);
    }

    function createContactTransport(options) {
        const opts = options || {};
        /* All optional: browser globals / setTimeout sleep / localStorage.
         * `storage` is accepted for API symmetry with the machine's seams;
         * the idempotency key lifecycle itself is machine-owned. */
        const doFetch = opts.fetch || function (path, init) {
            return fetch(path, init);
        };
        const doSleep = opts.sleep || function (ms) {
            return new Promise(function (resolve) {
                setTimeout(resolve, Math.min(ms, RETRY_AFTER_CAP_MS));
            });
        };

        return function transmit(data, keyInfo) {
            const payload = {
                name: String((data && data.name) || '').trim(),
                email: String((data && data.email) || '').trim(),
                message: String((data && data.message) || '').trim(),
                idempotencyKey: (keyInfo && keyInfo.idempotencyKey) || ''
            };
            let attempt = 0;

            function send() {
                attempt += 1;
                return doFetch(SUBMISSIONS_PATH, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Accept': 'application/json'
                    },
                    body: JSON.stringify(payload)
                }).then(function (response) {
                    if (response.ok) {
                        /* 2xx: the envelope, not the status line, decides
                         * success. A parse failure or success !== true is a
                         * terminal failure (no retry). The 202 DISPATCHING
                         * acceptance and an idempotent duplicate both carry
                         * success === true. */
                        return response.json().then(function (envelope) {
                            if (envelope && envelope.success === true) {
                                return envelope;
                            }
                            const terminal = new Error('response envelope was not a success');
                            terminal.kind = 'exhausted';
                            throw terminal;
                        }).catch(function (cause) {
                            if (cause && cause.kind === 'exhausted') {
                                throw cause;
                            }
                            /* unparseable body: terminal, never a success; the
                             * request was 2xx so the key is kept (a server-side
                             * accept may exist and dedupe on resubmit). */
                            const terminal = new Error('unparseable success envelope');
                            terminal.kind = 'exhausted';
                            throw terminal;
                        });
                    }
                    if (!isTransientStatus(response.status)) {
                        /* 400 / 404 / 409 and every other non-retryable 4xx:
                         * fail fast, no second attempt. */
                        const terminal = new Error('terminal HTTP ' + response.status);
                        terminal.kind = 'terminal-4xx';
                        terminal.status = response.status;
                        throw terminal;
                    }
                    /* transient: sleep, then retry while attempts remain */
                    const wait = retryAfterMs(response);
                    if (attempt >= MAX_ATTEMPTS) {
                        const exhausted = new Error('transient retries exhausted');
                        exhausted.kind = 'exhausted';
                        throw exhausted;
                    }
                    return doSleep(wait != null ? wait : backoffMs(attempt))
                        .then(send);
                }).catch(function (cause) {
                    /* a tagged rejection from the chain above propagates
                     * unchanged; an untagged one is a network rejection. */
                    if (cause && (cause.kind === 'terminal-4xx' || cause.kind === 'exhausted')) {
                        throw cause;
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        const exhausted = new Error('network retries exhausted');
                        exhausted.kind = 'exhausted';
                        throw exhausted;
                    }
                    /* network rejection: bounded retry through the sleep seam */
                    return doSleep(backoffMs(attempt)).then(send);
                });
            }

            return send();
        };
    }

    const TRANSMIT_DEFAULT = createContactTransport({});

    function truncate(value, max) {
        const text = value === null || value === undefined ? '' : String(value);
        return text.length > max ? text.slice(0, max) : text;
    }

    function copyFormData(data) {
        return {name: data.name, email: data.email, message: data.message};
    }

    function auditFormData(data) {
        return {
            name: truncate(data.name, CONTACT_FIELD_LIMITS.name),
            email: truncate(data.email, CONTACT_FIELD_LIMITS.email),
            message: truncate(data.message, CONTACT_FIELD_LIMITS.message)
        };
    }

    /* Append-only ring buffer. Every storage access degrades to memory on
     * unavailability, quota errors and corrupt JSON; auditing can never break
     * a transition nor rethrow. */
    function createAuditTrail(storage) {
        let memory = [];
        /* Set once a write has failed: storage then lags behind the in-memory
         * trail, so later reads must not overwrite the newest records with the
         * stale stored array. */
        let memoryOnly = false;

        function clearStored() {
            try {
                if (storage) {
                    storage.removeItem(CONTACT_AUDIT_KEY);
                }
            } catch (error) {
                /* keep the in-memory trail */
            }
        }

        /* Returns the stored records, or null when storage is unavailable,
         * empty or holding a value that is not a valid array of records. */
        function readStored() {
            let raw = null;
            try {
                raw = storage ? storage.getItem(CONTACT_AUDIT_KEY) : null;
            } catch (error) {
                return null;
            }
            if (raw === null || raw === undefined) {
                return null;
            }
            try {
                const parsed = JSON.parse(raw);
                if (!Array.isArray(parsed)) {
                    memory = [];
                    memoryOnly = false;
                    clearStored();
                    return null;
                }
                return parsed
                    .filter(function (record) {
                        return record && typeof record === 'object' && !Array.isArray(record);
                    })
                    .slice(0, CONTACT_AUDIT_MAX);
            } catch (error) {
                memory = [];
                memoryOnly = false;
                clearStored();
                return null;
            }
        }

        function readAll() {
            const stored = readStored();
            if (memoryOnly || stored === null) {
                /* degraded storage: the in-memory trail is the newest truth */
                return memory.slice(0, CONTACT_AUDIT_MAX);
            }
            memory = stored;
            return stored.slice();
        }

        function append(record) {
            const next = [record].concat(readAll()).slice(0, CONTACT_AUDIT_MAX);
            memory = next;
            try {
                if (storage) {
                    storage.setItem(CONTACT_AUDIT_KEY, JSON.stringify(next));
                }
                memoryOnly = false;
            } catch (error) {
                /* quota or private mode: the in-memory trail still carries the
                 * record and now outranks the stale stored value on read */
                memoryOnly = true;
            }
            return next.length;
        }

        return {readAll: readAll, append: append};
    }

    function createContactFormMachine(options) {
        const opts = options || {};
        const doc = opts.document;
        const storage = opts.storage || null;
        const transmit = typeof opts.transmit === 'function' ? opts.transmit : TRANSMIT_DEFAULT;
        const now = typeof opts.now === 'function' ? opts.now : function () {
            return new Date().toISOString();
        };

        const form = doc.getElementById('contact-form');
        const submitButton = doc.getElementById('contact-submit');
        const errorSummary = doc.getElementById('error-summary');
        const successRegion = doc.getElementById('contact-success');
        const statusRegion = doc.getElementById('contact-status');
        const errorNodes = {
            name: doc.getElementById('error-name'),
            email: doc.getElementById('error-email'),
            message: doc.getElementById('error-message')
        };
        const fields = {
            name: doc.getElementById('name'),
            email: doc.getElementById('email'),
            message: doc.getElementById('message')
        };
        const audit = createAuditTrail(storage);

        let state = 'idle';
        let pendingSnapshot = null;
        let pendingErrors = [];

        function readFormData() {
            return {
                name: fields.name ? fields.name.value : '',
                email: fields.email ? fields.email.value : '',
                message: fields.message ? fields.message.value : ''
            };
        }

        function writeFormData(data) {
            CONTACT_FIELD_IDS.forEach(function (id) {
                if (fields[id] && data && typeof data[id] === 'string') {
                    fields[id].value = data[id];
                }
            });
        }

        function resetFields() {
            writeFormData({name: '', email: '', message: ''});
        }

        function disableSubmit() {
            if (submitButton) {
                submitButton.disabled = true;
            }
        }

        function enableSubmit() {
            if (submitButton) {
                submitButton.disabled = false;
            }
        }

        function clearErrors() {
            if (errorSummary) {
                errorSummary.textContent = '';
            }
            CONTACT_FIELD_IDS.forEach(function (id) {
                const node = errorNodes[id];
                if (node) {
                    node.textContent = '';
                }
            });
        }

        /* textContent only: a hostile message must render as text, never as markup. */
        function renderErrors(errors) {
            clearErrors();
            let summary = '';
            (errors || []).forEach(function (item) {
                const node = item && errorNodes[item.field];
                if (node) {
                    node.textContent = item.message;
                } else if (item) {
                    summary = summary ? summary + ' ' + item.message : item.message;
                }
            });
            if (errorSummary) {
                errorSummary.textContent = summary;
            }
            /* bounded announcement into the hidden aria-live status region */
            if (statusRegion) {
                statusRegion.textContent = summary ? truncate(summary, 160) : '';
            }
        }

        /* aria-busy for the in-flight (validating/submitted) and retrying
         * phases: script-managed on the form; the hidden aria-live status
         * region is un-hidden while busy and re-hidden at rest. */
        function setStatusBusy(busy) {
            if (form) {
                form.setAttribute('aria-busy', busy ? 'true' : 'false');
            }
            if (statusRegion) {
                statusRegion.hidden = !busy;
                statusRegion.setAttribute('aria-busy', busy ? 'true' : 'false');
            }
        }

        /* driver/test seam: write bounded status text to the aria-live region
         * without touching state or the audit trail. */
        function setStatus(text) {
            if (statusRegion) {
                statusRegion.textContent = String(text == null ? '' : text);
            }
        }

        function clearStatusText() {
            if (statusRegion) {
                statusRegion.textContent = '';
            }
        }

        function showSuccess() {
            if (successRegion) {
                successRegion.hidden = false;
            }
        }

        function hideSuccess() {
            if (successRegion) {
                successRegion.hidden = true;
            }
        }

        function appendAudit(fromState, toState, data) {
            audit.append({
                timestamp: now(),
                fromState: fromState,
                toState: toState,
                formData: auditFormData(data)
            });
        }

        function applySideEffects(to) {
            if (to === 'validating' || to === 'submitted') {
                clearErrors();
                disableSubmit();
                setStatusBusy(true);
                clearStatusText();
                if (to === 'submitted' && statusRegion) {
                    statusRegion.textContent = 'Sending your message…';
                }
            } else if (to === 'success') {
                clearErrors();
                setStatusBusy(false);
                clearStatusText();
                hideSuccess();
                showSuccess();
                enableSubmit();
                resetFields();
            } else if (to === 'error') {
                setStatusBusy(false);
                enableSubmit();
                renderErrors(pendingErrors);
                rollback();
                pendingErrors = [];
            } else if (to === 'idle') {
                clearErrors();
                setStatusBusy(false);
                clearStatusText();
                hideSuccess();
                enableSubmit();
            }
        }

        /* The single transition entry point. Returns false and changes nothing
         * (no audit record either) for an event the current state does not
         * accept. */
        function go(event) {
            const row = CONTACT_EVENT_TARGETS[event];
            if (!row) {
                return false;
            }
            const target = row[state];
            if (!target || CONTACT_STATES.indexOf(target) === -1) {
                return false;
            }
            if (CONTACT_TRANSITIONS[state].indexOf(target) === -1) {
                return false;
            }
            const from = state;
            const data = readFormData();
            if (target === 'validating') {
                pendingSnapshot = copyFormData(data);
            }
            state = target;
            appendAudit(from, target, data);
            applySideEffects(target);
            return true;
        }

        function validate(data) {
            const errors = [];
            const name = (data.name || '').trim();
            const email = (data.email || '').trim();
            const message = (data.message || '').trim();

            if (!name) {
                errors.push({field: 'name', message: 'Please enter your name.'});
            } else if (name.length > CONTACT_FIELD_LIMITS.name) {
                errors.push({field: 'name', message: 'Name must be 100 characters or fewer.'});
            }

            if (!email) {
                errors.push({field: 'email', message: 'Please enter your email address.'});
            } else if (email.length > CONTACT_FIELD_LIMITS.email) {
                errors.push({field: 'email', message: 'Email must be 254 characters or fewer.'});
            } else if (!CONTACT_EMAIL_PATTERN.test(email)) {
                errors.push({field: 'email', message: 'Please enter a valid email address, for example you@example.com.'});
            }

            if (!message) {
                errors.push({field: 'message', message: 'Please enter a message.'});
            } else if (message.length > CONTACT_FIELD_LIMITS.message) {
                errors.push({field: 'message', message: 'Message must be 2000 characters or fewer.'});
            }

            return errors;
        }

        /* Restores the pre-submission snapshot into all three inputs. Idempotent:
         * a second call with the same snapshot changes nothing. Never clears a
         * value and never leaves the submit button disabled. */
        function rollback() {
            if (pendingSnapshot) {
                writeFormData(copyFormData(pendingSnapshot));
            }
            enableSubmit();
        }

        /* Idempotency subsystem (REQ-02): one key per submission, stable
         * across every retry of the same payload, rotated on payload change.
         * The value is inert (small, self-overwriting) if it ever outlives
         * the form. All access degrades to in-memory like the audit trail. */
        const IDEMPOTENCY_KEY = 'utiltools.contact.idempotency.v1';
        const IDEMPOTENCY_LIMIT = 128;

        function fnv1a32(str) {
            let hash = 0x811c9dc5;
            for (let i = 0; i < str.length; i += 1) {
                hash ^= str.charCodeAt(i);
                hash = (hash * 0x01000193) >>> 0;
            }
            return ('00000000' + hash.toString(16)).slice(-8);
        }

        /* 16-hex FNV-1a over the fixed-key-order JSON of the trimmed fields
         * (no crypto dependency is available in the bare vm sandbox). */
        function fingerprint(data) {
            const normalized = {
                message: String((data && data.message) || '').trim(),
                name: String((data && data.name) || '').trim(),
                email: String((data && data.email) || '').trim()
            };
            const json = JSON.stringify(normalized);
            return fnv1a32(json) + fnv1a32(fnv1a32(json));
        }

        function newIdempotencyKey(data) {
            const randomPart = (Math.random().toString(36).slice(2, 12) + Math.random().toString(36).slice(2, 12)).slice(0, 12);
            /* ~44 chars: well inside the server's 128-char @Size limit. */
            return ('ct-' + Date.now().toString(36) + '-' + randomPart + '-' + fingerprint(data)).slice(0, IDEMPOTENCY_LIMIT);
        }

        function acquireIdempotencyKey(data) {
            const fp = fingerprint(data);
            let stored = null;
            try {
                stored = storage ? JSON.parse(storage.getItem(IDEMPOTENCY_KEY) || 'null') : null;
            } catch (error) {
                stored = null;
            }
            if (stored && stored.fingerprint === fp &&
                typeof stored.key === 'string' && stored.key.length > 0 &&
                stored.key.length <= IDEMPOTENCY_LIMIT) {
                /* reuse only while the payload fingerprint is unchanged */
                return stored.key;
            }
            /* rotation: payload changed (or nothing usable is stored) */
            const key = newIdempotencyKey(data);
            try {
                if (storage) {
                    storage.setItem(IDEMPOTENCY_KEY, JSON.stringify({fingerprint: fp, key: key}));
                }
            } catch (error) {
                /* storage degradation: the key still protects this submit window */
            }
            return key;
        }

        /* "Spending" a key: after success or a terminal 4xx the current key
         * must not be replayed by the next submission of the same payload, so
         * a fresh key is written under the same fingerprint. An exhausted
         * transient set is none of those: the stored key is kept so the user's
         * immediate resubmit dedupes against a submission the server may have
         * accepted. */
        function releaseIdempotencyKey(data) {
            try {
                if (storage) {
                    storage.setItem(IDEMPOTENCY_KEY, JSON.stringify({fingerprint: fingerprint(data), key: newIdempotencyKey(data)}));
                }
            } catch (error) {
                /* inert leftover, harmless */
            }
        }

        function submitAttempt() {
            if (state === 'validating' || state === 'submitted') {
                /* re-entrancy guard: one click must not fire two transitions */
                return false;
            }
            if (state === 'success') {
                /* the declared success -> idle edge, fired by the handler so a
                 * second submission is a real transition (audited) instead of a
                 * silent no-op */
                go('reset');
            }
            if (!go('submit')) {
                return false;
            }
            const data = pendingSnapshot ? copyFormData(pendingSnapshot) : readFormData();
            const errors = validate(data);
            if (errors.length > 0) {
                pendingErrors = errors;
                go('validation-fail');
                return false;
            }
            const idempotencyKey = acquireIdempotencyKey(data);
            go('validation-pass');
            let dispatched;
            try {
                dispatched = transmit(data, { idempotencyKey: idempotencyKey });
            } catch (error) {
                dispatched = Promise.reject(error);
            }
            Promise.resolve(dispatched).then(function (result) {
                /* success: the ApiResponse envelope's success flag, or the
                 * legacy {ok:true} shape that test fakes still return.
                 * Spending the key so a resubmit of the same payload is a
                 * fresh submission. */
                releaseIdempotencyKey(data);
                go('dispatch-resolved');
            }, function (error) {
                if (error && error.kind === 'terminal-4xx') {
                    /* a terminal 4xx will never succeed on retry: spend the
                     * key so the next submission of this payload is fresh. */
                    releaseIdempotencyKey(data);
                    pendingErrors = [{field: 'form', message: 'Your submission could not be accepted. Please try again.'}];
                    go('dispatch-rejected');
                    return;
                }
                /* exhausted transient failover: keep the stored key so an
                 * immediate resubmit dedupes against a submission the
                 * server may have accepted. */
                pendingErrors = [{field: 'form', message: 'We could not send your message. Please try again.'}];
                go('dispatch-rejected');
            });
            return true;
        }

        function onSubmit(event) {
            if (event && typeof event.preventDefault === 'function') {
                event.preventDefault();
            }
            submitAttempt();
        }

        return {
            getState: function () {
                return state;
            },
            go: go,
            submit: submitAttempt,
            onSubmit: onSubmit,
            rollback: rollback,
            renderErrors: renderErrors,
            auditRecords: function () {
                return audit.readAll();
            },
            pendingSnapshot: function () {
                return pendingSnapshot ? copyFormData(pendingSnapshot) : null;
            },
            setStatus: setStatus
        };
    }

    global.ContactFormStateMachine = {
        CONTACT_STATES: CONTACT_STATES,
        CONTACT_TRANSITIONS: CONTACT_TRANSITIONS,
        CONTACT_AUDIT_KEY: CONTACT_AUDIT_KEY,
        CONTACT_AUDIT_FIELDS: CONTACT_AUDIT_FIELDS,
        CONTACT_AUDIT_MAX: CONTACT_AUDIT_MAX,
        createContactFormMachine: createContactFormMachine,
        createContactTransport: createContactTransport,
        CT_MAX_ATTEMPTS: MAX_ATTEMPTS,
        CT_RETRY_AFTER_CAP_MS: RETRY_AFTER_CAP_MS,
        CT_EXP_BASE_MS: EXP_BASE_MS,
        CT_EXP_CAP_MS: EXP_CAP_MS,
        CT_SUBMISSIONS_PATH: SUBMISSIONS_PATH
    };

    if (typeof global.document === 'undefined' || global.document === null) {
        return;
    }
    const hostForm = global.document.getElementById('contact-form');
    if (!hostForm) {
        return;
    }
    let hostStorage = null;
    try {
        hostStorage = global.localStorage || null;
    } catch (error) {
        hostStorage = null;
    }
    /* Shipped default transport: real fetch POST to the submissions endpoint.
     * The machine's own `opts.transmit` seam still lets tests substitute a
     * fake transport; the idempotency key lives in the machine's storage. */
    const machine = createContactFormMachine({
        document: global.document,
        storage: hostStorage,
        transmit: TRANSMIT_DEFAULT
    });
    hostForm.addEventListener('submit', machine.onSubmit);
    global.contactFormMachine = machine;
}(typeof window !== 'undefined' ? window : this));
