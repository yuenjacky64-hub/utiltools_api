package com.example.utiltools.audit.service;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.common.ReplayConflictException;
import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.UrlRequest;
import com.example.utiltools.crypto.dto.UrlResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.dto.JsonFlattenRequest;
import com.example.utiltools.json.dto.JsonFlattenResponse;
import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonMergePatchRequest;
import com.example.utiltools.json.dto.JsonMergePatchResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonUnflattenRequest;
import com.example.utiltools.json.dto.JsonUnflattenResponse;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.dto.JsonValidateRequest;
import com.example.utiltools.json.dto.JsonValidateResponse;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.dto.TextReplaceRequest;
import com.example.utiltools.text.dto.TextReplaceResponse;
import com.example.utiltools.text.dto.TextStatsRequest;
import com.example.utiltools.text.dto.TextStatsResponse;
import com.example.utiltools.text.dto.TextTransformRequest;
import com.example.utiltools.text.dto.TextTransformResponse;
import com.example.utiltools.text.service.TextService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Journalling facade over the journalled utility operations.
 *
 * <p>Every journalled call begins a PENDING entry, invokes the underlying service once,
 * and records SUCCEEDED or FAILED before returning/rethrowing. The original exception is
 * never masked. {@link #replayInvoke(OperationType, Object)} re-dispatches the same calls
 * without journalling, so replay can never append an entry.</p>
 */
@Service
public class AuditedOperations {

    private static final String BAD_REQUEST = "BAD_REQUEST";
    private static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";

    private final DateTimeService dateTimeService;
    private final CryptoService cryptoService;
    private final JsonService jsonService;
    private final TextService textService;
    private final AuditJournalService journal;

    public AuditedOperations(DateTimeService dateTimeService,
                             CryptoService cryptoService,
                             JsonService jsonService,
                             TextService textService,
                             AuditJournalService journal) {
        this.dateTimeService = dateTimeService;
        this.cryptoService = cryptoService;
        this.jsonService = jsonService;
        this.textService = textService;
        this.journal = journal;
    }

    public DateTimeConvertResponse convert(DateTimeConvertRequest request) {
        return record(OperationType.DATETIME_CONVERT, request, () -> dateTimeService.convert(request));
    }

    public DateTimeDiffResponse diff(DateTimeDiffRequest request) {
        return record(OperationType.DATETIME_DIFF, request, () -> dateTimeService.diff(request));
    }

    public DateTimeShiftResponse shift(DateTimeShiftRequest request) {
        return record(OperationType.DATETIME_SHIFT, request, () -> dateTimeService.shift(request));
    }

    public CurrentTimeResponse current(String timeZone) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("timeZone", timeZone);
        return record(OperationType.DATETIME_CURRENT, request, () -> dateTimeService.getCurrentTime(timeZone));
    }

    public HashResponse hash(HashRequest request) {
        return record(OperationType.CRYPTO_HASH, request, () -> cryptoService.hash(request));
    }

    public Base64Response base64(Base64Request request) {
        return record(OperationType.CRYPTO_BASE64, request, () -> cryptoService.base64(request));
    }

    public UrlResponse url(UrlRequest request) {
        return record(OperationType.CRYPTO_URL, request, () -> cryptoService.url(request));
    }

    public UuidResponse uuid(int count, boolean compact, String version) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("count", count);
        request.put("compact", compact);
        request.put("version", version);
        return record(OperationType.CRYPTO_UUID, request,
                () -> cryptoService.generateUuids(count, compact, version));
    }

    public JsonFormatResponse format(JsonFormatRequest request) {
        return record(OperationType.JSON_FORMAT, request, () -> jsonService.format(request));
    }

    public JsonValidateResponse validate(JsonValidateRequest request) {
        return record(OperationType.JSON_VALIDATE, request, () -> jsonService.validate(request));
    }

    public JsonQueryResponse query(JsonQueryRequest request) {
        return record(OperationType.JSON_QUERY, request, () -> jsonService.query(request));
    }

    public JsonFlattenResponse flatten(JsonFlattenRequest request) {
        return record(OperationType.JSON_FLATTEN, request, () -> jsonService.flatten(request));
    }

    public JsonUnflattenResponse unflatten(JsonUnflattenRequest request) {
        return record(OperationType.JSON_UNFLATTEN, request, () -> jsonService.unflatten(request));
    }

    public JsonMergePatchResponse mergePatch(JsonMergePatchRequest request) {
        return record(OperationType.JSON_MERGE_PATCH, request, () -> jsonService.mergePatch(request));
    }

    public TextTransformResponse transform(TextTransformRequest request) {
        return record(OperationType.TEXT_TRANSFORM, request, () -> textService.transform(request));
    }

    public TextStatsResponse stats(TextStatsRequest request) {
        return record(OperationType.TEXT_STATS, request, () -> textService.stats(request));
    }

    public TextReplaceResponse replace(TextReplaceRequest request) {
        return record(OperationType.TEXT_REPLACE, request, () -> textService.replace(request));
    }

    /**
     * Re-runs a stored operation without journalling; the single entry point used by replay.
     */
    public Object replayInvoke(OperationType operation, Object request) {
        return switch (operation) {
            case DATETIME_CONVERT -> dateTimeService.convert((DateTimeConvertRequest) request);
            case DATETIME_DIFF -> dateTimeService.diff((DateTimeDiffRequest) request);
            case DATETIME_SHIFT -> dateTimeService.shift((DateTimeShiftRequest) request);
            case DATETIME_CURRENT -> dateTimeService.getCurrentTime(timeZoneOf(request));
            case CRYPTO_HASH -> cryptoService.hash((HashRequest) request);
            case CRYPTO_BASE64 -> cryptoService.base64((Base64Request) request);
            case CRYPTO_URL -> cryptoService.url((UrlRequest) request);
            case CRYPTO_UUID -> cryptoService.generateUuids(intOf(request, "count"), booleanOf(request, "compact"),
                    stringOf(request, "version"));
            case JSON_FORMAT -> jsonService.format((JsonFormatRequest) request);
            case JSON_VALIDATE -> jsonService.validate((JsonValidateRequest) request);
            case JSON_QUERY -> jsonService.query((JsonQueryRequest) request);
            case JSON_FLATTEN -> jsonService.flatten((JsonFlattenRequest) request);
            case JSON_UNFLATTEN -> jsonService.unflatten((JsonUnflattenRequest) request);
            case JSON_MERGE_PATCH -> jsonService.mergePatch((JsonMergePatchRequest) request);
            case TEXT_TRANSFORM -> textService.transform((TextTransformRequest) request);
            case TEXT_STATS -> textService.stats((TextStatsRequest) request);
            case TEXT_REPLACE -> textService.replace((TextReplaceRequest) request);
            // Asynchronous batch hashing is audit-only: a BATCH_HASH entry can never be replayed,
            // because there is no servlet-bound payload to re-run and the operation is
            // non-deterministic. Defence in depth: claimReplay refuses the same entries.
            case BATCH_HASH -> throw new ReplayConflictException(null,
                    "batch hashing runs asynchronously and is non-replayable");
        };
    }

    private <T> T record(OperationType operation, Object request, Supplier<T> call) {
        AuditEntry entry = beginQuietly(operation, request);
        String id = entry == null ? null : entry.id();
        T result;
        try {
            result = call.get();
        } catch (IllegalArgumentException e) {
            recordFailure(id, BAD_REQUEST);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(id, INTERNAL_SERVER_ERROR);
            throw e;
        }
        recordSuccess(id, result);
        return result;
    }

    /**
     * Journalling is best effort end-to-end: a failure while beginning the entry (unserialisable
     * request payload, for instance) yields a {@code null} entry and the operation still runs.
     */
    private AuditEntry beginQuietly(OperationType operation, Object request) {
        try {
            return journal.begin(operation, request, RequestContext.get());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A journal fault (no entry, entry evicted by a concurrent burst, or already advanced) is
     * swallowed: the operation outcome is what the caller asked for and must never be masked by
     * the journal writing 500s over a success or over a legitimate {@code BAD_REQUEST}.
     */
    private void recordSuccess(String id, Object result) {
        if (id == null) {
            return;
        }
        try {
            journal.succeed(id, result);
        } catch (RuntimeException e) {
            // best effort: the journal must not change the operation outcome
        }
    }

    private void recordFailure(String id, String errorCode) {
        if (id == null) {
            return;
        }
        try {
            journal.fail(id, errorCode);
        } catch (RuntimeException e) {
            // best effort: the original exception is rethrown by the caller
        }
    }

    private static String timeZoneOf(Object request) {
        if (request instanceof Map<?, ?> map) {
            Object value = map.get("timeZone");
            return value == null ? null : value.toString();
        }
        return null;
    }

    private static int intOf(Object request, String key) {
        if (request instanceof Map<?, ?> map && map.get(key) instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static String stringOf(Object request, String key) {
        if (request instanceof Map<?, ?> map && map.get(key) != null) {
            return map.get(key).toString();
        }
        return null;
    }

    private static boolean booleanOf(Object request, String key) {
        if (request instanceof Map<?, ?> map && map.get(key) instanceof Boolean value) {
            return value;
        }
        return false;
    }
}
