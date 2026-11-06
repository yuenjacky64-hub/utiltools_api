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

    /* Network seam. This task ships the default, so the form adds no network
     * contract; a real endpoint is future work. */
    const TRANSMIT_DEFAULT = function () {
        return Promise.resolve({ok: true});
    };

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

        function clearStored() {
            try {
                if (storage) {
                    storage.removeItem(CONTACT_AUDIT_KEY);
                }
            } catch (error) {
                /* keep the in-memory trail */
            }
        }

        function readAll() {
            let raw = null;
            try {
                raw = storage ? storage.getItem(CONTACT_AUDIT_KEY) : null;
            } catch (error) {
                raw = null;
            }
            if (raw === null || raw === undefined) {
                return memory.slice();
            }
            try {
                const parsed = JSON.parse(raw);
                if (!Array.isArray(parsed)) {
                    memory = [];
                    clearStored();
                    return [];
                }
                return parsed
                    .filter(function (record) {
                        return record && typeof record === 'object' && !Array.isArray(record);
                    })
                    .slice(0, CONTACT_AUDIT_MAX);
            } catch (error) {
                memory = [];
                clearStored();
                return [];
            }
        }

        function append(record) {
            const next = [record].concat(readAll()).slice(0, CONTACT_AUDIT_MAX);
            memory = next;
            try {
                if (storage) {
                    storage.setItem(CONTACT_AUDIT_KEY, JSON.stringify(next));
                }
            } catch (error) {
                /* quota or private mode: the in-memory trail still carries the record */
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
            } else if (to === 'success') {
                clearErrors();
                hideSuccess();
                showSuccess();
                enableSubmit();
                resetFields();
            } else if (to === 'error') {
                enableSubmit();
                renderErrors(pendingErrors);
                rollback();
                pendingErrors = [];
            } else if (to === 'idle') {
                clearErrors();
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

        function submitAttempt() {
            if (state === 'validating' || state === 'submitted') {
                /* re-entrancy guard: one click must not fire two transitions */
                return false;
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
            go('validation-pass');
            let dispatched;
            try {
                dispatched = transmit(copyFormData(data));
            } catch (error) {
                dispatched = Promise.reject(error);
            }
            Promise.resolve(dispatched).then(function () {
                go('dispatch-resolved');
            }, function () {
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
            }
        };
    }

    global.ContactFormStateMachine = {
        CONTACT_STATES: CONTACT_STATES,
        CONTACT_TRANSITIONS: CONTACT_TRANSITIONS,
        CONTACT_AUDIT_KEY: CONTACT_AUDIT_KEY,
        CONTACT_AUDIT_FIELDS: CONTACT_AUDIT_FIELDS,
        CONTACT_AUDIT_MAX: CONTACT_AUDIT_MAX,
        createContactFormMachine: createContactFormMachine
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
    const machine = createContactFormMachine({
        document: global.document,
        storage: hostStorage,
        transmit: TRANSMIT_DEFAULT
    });
    hostForm.addEventListener('submit', machine.onSubmit);
    global.contactFormMachine = machine;
}(typeof window !== 'undefined' ? window : this));
