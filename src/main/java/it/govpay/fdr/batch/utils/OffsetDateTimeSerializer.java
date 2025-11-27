package it.govpay.fdr.batch.utils;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

import it.govpay.fdr.batch.Costanti;

/**
 * Custom serializer for OffsetDateTime to ensure consistent date format in JSON output.
 * Uses configurable date pattern for serialization (default: yyyy-MM-dd'T'HH:mm:ss.SSSXXX).
 */
public class OffsetDateTimeSerializer extends StdScalarSerializer<OffsetDateTime> {

	private static final long serialVersionUID = 1L;

	private transient DateTimeFormatter formatter;

	/**
	 * Default constructor using standard timestamp format with timezone.
	 */
	public OffsetDateTimeSerializer() {
		this(Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX);
	}

	/**
	 * Constructor with custom date format pattern.
	 *
	 * @param format the date format pattern to use for serialization
	 */
	public OffsetDateTimeSerializer(String format) {
		super(OffsetDateTime.class);
		this.formatter = DateTimeFormatter.ofPattern(format);
	}

	@Override
	public void serialize(OffsetDateTime dateTime, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
		String dateTimeAsString = dateTime != null ? this.formatter.format(dateTime) : null;
		jsonGenerator.writeString(dateTimeAsString);
	}
}
