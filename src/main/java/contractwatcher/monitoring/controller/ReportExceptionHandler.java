package contractwatcher.monitoring.controller;

import contractwatcher.monitoring.exception.ReportValidationException;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.transaction.TransactionSystemException;

@RestControllerAdvice
final class ReportExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ReportExceptionHandler.class);

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> invalidRequest() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Некорректное тело запроса.", Map.of());
	}

	@ExceptionHandler(ReportValidationException.class)
	ResponseEntity<ProblemDetail> invalidReport(ReportValidationException exception) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_REPORT_INVALID",
				"Отчет содержит некорректные данные.", exception.errors());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ProblemDetail> unsupportedMediaType() {
		return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
				"Требуется Content-Type application/json.", Map.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> processingFailure(Exception exception) {
		if (storageUnavailable(exception)) {
			return problem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
					"Хранилище отчетов временно недоступно.", Map.of());
		}
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"Не удалось обработать отчет.", Map.of());
	}

	private boolean storageUnavailable(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			// Ошибка rollback может скрыть исходную потерю соединения.
			if (cause instanceof TransactionSystemException transaction
					&& transaction.getApplicationException() != null
					&& storageUnavailable(transaction.getApplicationException())) {
				return true;
			}
			if (cause instanceof DataAccessResourceFailureException
					|| cause instanceof TransientDataAccessResourceException) {
				return true;
			}
			if (cause instanceof SQLException sql && sql.getSQLState() != null
					&& (sql.getSQLState().startsWith("08")
							|| List.of("57P01", "57P02", "57P03").contains(sql.getSQLState()))) {
				return true;
			}
		}
		return false;
	}

	private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
			Map<String, List<String>> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("about:blank"));
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create("/internal/v1/reports"));
		problem.setProperty("code", code);
		problem.setProperty("errors", errors);
		LOG.warn("POST /internal/v1/reports: status={}, code={}", status.value(), code);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}
}
