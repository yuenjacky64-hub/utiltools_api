package com.example.utiltools.audit.service;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.crypto.dto.Base64Request;
import com.example.utiltools.crypto.dto.Base64Response;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.dto.UuidResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.dto.JsonValidateRequest;
import com.example.utiltools.json.dto.JsonValidateResponse;
import com.example.utiltools.json.service.JsonService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Journalling facade over the nine existing utility operations.
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
    private final AuditJournalService journal;

    public AuditedOperations(DateTimeService dateTimeService,
                             CryptoService cryptoService,
                             JsonService jsonService,
                             AuditJournalService journal) {
        this.dateTimeService = dateTimeService;
        this.cryptoService = cryptoService;
        this.jsonService = jsonService;
        this.journal = journal;
    }

    public DateTimeConvertResponse convert(DateTimeConvertRequest request) {
        return record(OperationType.DATETIME_CONVERT, request, () -> dateTimeService.convert(request));
    }

    public DateTimeDiffResponse diff(DateTimeDiffRequest request) {
        return record(OperationType.DATETIME_DIFF, request, () -> dateTimeService.diff(request));
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

    public UuidResponse uuid(int count, boolean compact) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("count", count);
        request.put("compact", compact);
        return record(OperationType.CRYPTO_UUID, request, () -> cryptoService.generateUuids(count, compact));
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

    /**
     * Re-runs a stored operation without journalling; the single entry point used by replay.
     */
    public Object replayInvoke(OperationType operation, Object request) {
        return switch (operation) {
            case DATETIME_CONVERT -> dateTimeService.convert((DateTimeConvertRequest) request);
            case DATETIME_DIFF -> dateTimeService.diff((DateTimeDiffRequest) request);
            case DATETIME_CURRENT -> dateTimeService.getCurrentTime(timeZoneOf(request));
            case CRYPTO_HASH -> cryptoService.hash((HashRequest) request);
            case CRYPTO_BASE64 -> cryptoService.base64((Base64Request) request);
            case CRYPTO_UUID -> cryptoService.generateUuids(intOf(request, "count"), booleanOf(request, "compact"));
            case JSON_FORMAT -> jsonService.format((JsonFormatRequest) request);
            case JSON_VALIDATE -> jsonService.validate((JsonValidateRequest) request);
            case JSON_QUERY -> jsonService.query((JsonQueryRequest) request);
        };
    }

    private <T> T record(OperationType operation, Object request, Supplier<T> call) {
        AuditEntry entry = journal.begin(operation, request, RequestContext.get());
        T result;
        try {
            result = call.get();
        } catch (IllegalArgumentException e) {
            recordFailure(entry.id(), BAD_REQUEST);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(entry.id(), INTERNAL_SERVER_ERROR);
            throw e;
        }
        recordSuccess(entry.id(), result);
        return result;
    }

    /**
     * A journal transition fault (entry evicted by a concurrent burst, or already advanced) is
     * swallowed: the operation outcome is what the caller asked for and must never be masked by
     * the journal writing 500s over a success or over a legitimate {@code BAD_REQUEST}.
     */
    private void recordSuccess(String id, Object result) {
        try {
            journal.succeed(id, result);
        } catch (RuntimeException e) {
            // best effort: the journal must not change the operation outcome
        }
    }

    private void recordFailure(String id, String errorCode) {
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

    private static boolean booleanOf(Object request, String key) {
        if (request instanceof Map<?, ?> map && map.get(key) instanceof Boolean value) {
            return value;
        }
        return false;
    }
}
