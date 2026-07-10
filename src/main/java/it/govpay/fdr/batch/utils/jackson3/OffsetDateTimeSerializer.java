package it.govpay.fdr.batch.utils.jackson3;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdScalarSerializer;

import it.govpay.fdr.batch.Costanti;

/**
 * Serializer Jackson 3 per OffsetDateTime, garantisce un formato data coerente in output.
 * Usa il pattern configurabile (default: yyyy-MM-dd'T'HH:mm:ss.SSSXXX).
 * <p>
 * Gemello Jackson 3 di {@link it.govpay.fdr.batch.utils.OffsetDateTimeSerializer} (Jackson 2),
 * mantenuto separato perche' il client OpenAPI generato usa ancora Jackson 2.
 */
public class OffsetDateTimeSerializer extends StdScalarSerializer<OffsetDateTime> {

	private final transient DateTimeFormatter formatter;

	/**
	 * Costruttore di default con formato timestamp standard con timezone.
	 */
	public OffsetDateTimeSerializer() {
		this(Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX);
	}

	/**
	 * Costruttore con pattern di formato personalizzato.
	 *
	 * @param format il pattern di formato data da usare per la serializzazione
	 */
	public OffsetDateTimeSerializer(String format) {
		super(OffsetDateTime.class);
		this.formatter = DateTimeFormatter.ofPattern(format);
	}

	@Override
	public void serialize(OffsetDateTime value, JsonGenerator gen, SerializationContext ctxt) {
		if (value != null) {
			gen.writeString(this.formatter.format(value));
		} else {
			gen.writeNull();
		}
	}
}
