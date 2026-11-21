package cl.duoc.fnalertas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Función serverless (AWS Lambda) disparada por la cola SQS "inventario-alertas".
 *
 * Por cada mensaje:
 *  - Si es válido, registra la alerta de stock bajo en CloudWatch Logs.
 *  - Si es inválido, lo informa como fallido: SQS lo reintenta y, tras 3 intentos,
 *    lo mueve a la cola de mensajes fallidos "alertas-fallidas" (DLQ).
 */
public class ProcesarAlertaHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public SQSBatchResponse handleRequest(SQSEvent evento, Context context) {
        LambdaLogger logger = context.getLogger();
        List<SQSBatchResponse.BatchItemFailure> fallidos = new ArrayList<>();

        for (SQSEvent.SQSMessage mensaje : evento.getRecords()) {
            try {
                AlertaStock alerta = objectMapper.readValue(mensaje.getBody(), AlertaStock.class);
                if (!alerta.esValida()) {
                    throw new IllegalArgumentException("Mensaje con datos incompletos o negativos");
                }
                logger.log(String.format(
                        "ALERTA STOCK BAJO | producto=%d | stockActual=%d | stockMinimo=%d | fecha=%s | reponer=%d unidades",
                        alerta.productoId(), alerta.stockActual(), alerta.stockMinimo(), alerta.fecha(),
                        unidadesAReponer(alerta)));
            } catch (Exception ex) {
                logger.log("ERROR procesando mensaje " + mensaje.getMessageId() + ": " + ex.getMessage());
                fallidos.add(new SQSBatchResponse.BatchItemFailure(mensaje.getMessageId()));
            }
        }

        logger.log("Mensajes recibidos: " + evento.getRecords().size() + ", fallidos: " + fallidos.size());
        return new SQSBatchResponse(fallidos);
    }

    /**
     * Sugerencia de reposición: lo necesario para volver al doble del stock mínimo.
     */
    static int unidadesAReponer(AlertaStock alerta) {
        return Math.max(0, alerta.stockMinimo() * 2 - alerta.stockActual());
    }
}
