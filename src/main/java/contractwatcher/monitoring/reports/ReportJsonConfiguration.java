package contractwatcher.monitoring.reports;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
class ReportJsonConfiguration {

	@Bean
	JsonMapperBuilderCustomizer strictReportJson() {
		SimpleModule enums = new SimpleModule();
		enums.addDeserializer(ValidationReport.Status.class, new ExactEnumDeserializer<>(ValidationReport.Status.class));
		enums.addDeserializer(ReportViolation.Kind.class, new ExactEnumDeserializer<>(ReportViolation.Kind.class));
		enums.addDeserializer(ReportViolation.JsonType.class, new ExactEnumDeserializer<>(ReportViolation.JsonType.class));
		return builder -> builder.addModule(enums)
				.withCoercionConfig(LogicalType.Integer, config -> config
						.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
				.withCoercionConfig(LogicalType.Textual, config -> config
						.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
	}
}
