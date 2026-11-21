package cl.duoc.fnalertas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcesarAlertaHandlerTest {

    private final ProcesarAlertaHandler handler = new ProcesarAlertaHandler();
    private Context context;

    @BeforeEach
    void setUp() {
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @Test
    void mensajeValidoSeProcesaSinFallos() {
        SQSEvent evento = evento(mensaje("1",
                "{\"productoId\":1,\"stockActual\":3,\"stockMinimo\":5,\"fecha\":\"2026-11-21T10:00:00\"}"));

        SQSBatchResponse respuesta = handler.handleRequest(evento, context);

        assertTrue(respuesta.getBatchItemFailures().isEmpty());
    }

    @Test
    void mensajeInvalidoSeInformaComoFallido() {
        SQSEvent evento = evento(
                mensaje("1", "{\"productoId\":1,\"stockActual\":3,\"stockMinimo\":5}"),
                mensaje("2", "esto no es json"),
                mensaje("3", "{\"productoId\":2}"));

        SQSBatchResponse respuesta = handler.handleRequest(evento, context);

        assertEquals(2, respuesta.getBatchItemFailures().size());
        assertEquals("2", respuesta.getBatchItemFailures().get(0).getItemIdentifier());
        assertEquals("3", respuesta.getBatchItemFailures().get(1).getItemIdentifier());
    }

    @Test
    void calculaUnidadesAReponer() {
        assertEquals(7, ProcesarAlertaHandler.unidadesAReponer(new AlertaStock(1L, 3, 5, null)));
        assertEquals(0, ProcesarAlertaHandler.unidadesAReponer(new AlertaStock(1L, 20, 5, null)));
    }

    private SQSEvent evento(SQSEvent.SQSMessage... mensajes) {
        SQSEvent evento = new SQSEvent();
        evento.setRecords(List.of(mensajes));
        return evento;
    }

    private SQSEvent.SQSMessage mensaje(String id, String cuerpo) {
        SQSEvent.SQSMessage mensaje = new SQSEvent.SQSMessage();
        mensaje.setMessageId(id);
        mensaje.setBody(cuerpo);
        return mensaje;
    }
}
