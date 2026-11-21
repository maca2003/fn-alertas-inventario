# fn-alertas-inventario

Función **serverless (AWS Lambda, Java 21)** que procesa las alertas de stock bajo del sistema de microservicios.

## Requerimiento funcional

Cuando una venta deja un producto con stock igual o menor a su stock mínimo, **ms-inventario** publica un evento en la cola **Amazon SQS `inventario-alertas`**. Esta función se dispara automáticamente con cada mensaje y:

1. Lee el mensaje (`productoId`, `stockActual`, `stockMinimo`, `fecha`).
2. Registra la alerta en **CloudWatch Logs** con la cantidad sugerida a reponer (hasta el doble del stock mínimo).
3. Si el mensaje es inválido, lo informa como fallido: SQS lo reintenta y, después de 3 intentos, lo mueve a la cola **`alertas-fallidas` (DLQ)**. Si la DLQ recibe mensajes, se activa la alarma de CloudWatch **`alertas-fallidas-con-mensajes`**.

```
ms-inventario ──(productor)──▶ SQS inventario-alertas ──(trigger)──▶ Lambda fn-alertas-inventario ──▶ CloudWatch Logs
                                        │ 3 intentos fallidos
                                        ▼
                                SQS alertas-fallidas (DLQ) ──▶ Alarma CloudWatch
```

Mensaje de ejemplo:
```json
{ "productoId": 1, "stockActual": 3, "stockMinimo": 5, "fecha": "2026-11-21T10:00:00" }
```

## Estructura

```
├── src/main/java/cl/duoc/fnalertas
│   ├── ProcesarAlertaHandler.java   -> Handler de la Lambda (RequestHandler<SQSEvent, SQSBatchResponse>)
│   └── AlertaStock.java             -> Mensaje recibido
├── src/test/java/...                -> Pruebas unitarias (JUnit 5 + Mockito)
├── infraestructura/template.yaml    -> CloudFormation: SQS, DLQ, Lambda, trigger y alarma
├── eventos/evento-prueba.json       -> Evento SQS de ejemplo para probar la función
└── .github/workflows/ci-cd.yml      -> Pipeline CI/CD
```

## Tecnologías

- Java 21 · Maven · AWS Lambda (runtime `java21`)
- `aws-lambda-java-core`, `aws-lambda-java-events`, Jackson
- Amazon SQS, AWS CloudFormation, Amazon S3, Amazon CloudWatch
- GitHub Actions

## Compilar y probar en local

```bash
git clone https://github.com/maca2003/fn-alertas-inventario.git
cd fn-alertas-inventario
mvn clean package
```
Genera `target/fn-alertas-inventario-1.0.0.jar` (un solo jar con todas las dependencias, listo para Lambda).

## Despliegue automático (CI/CD)

El pipeline `.github/workflows/ci-cd.yml` se ejecuta en cada push:

| Etapa | Ramas | Qué hace |
|---|---|---|
| Build y test | `main`, `develop`, PR | `mvn clean package` (compila y ejecuta las pruebas) |
| Provisionar y desplegar | `main` | Crea el bucket S3 si no existe, sube el jar y despliega el stack de CloudFormation `fn-alertas-inventario` (SQS, DLQ, Lambda, trigger y alarma). Al final invoca la función con `eventos/evento-prueba.json` |

### Secrets del repositorio (Settings → Secrets and variables → Actions)

| Secret | Valor |
|---|---|
| `AWS_ACCESS_KEY_ID` | Learner Lab → AWS Details → AWS CLI |
| `AWS_SECRET_ACCESS_KEY` | Learner Lab → AWS Details → AWS CLI |
| `AWS_SESSION_TOKEN` | Learner Lab → AWS Details → AWS CLI |

> En AWS Academy Learner Lab las credenciales cambian cada vez que se inicia el laboratorio: actualiza los 3 secrets antes de ejecutar el pipeline.

## Despliegue manual (alternativa)

Con AWS CLI configurado:
```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUCKET=fn-alertas-inventario-$ACCOUNT_ID
aws s3api create-bucket --bucket $BUCKET
aws s3 cp target/fn-alertas-inventario-1.0.0.jar s3://$BUCKET/codigo/manual.jar
aws cloudformation deploy --stack-name fn-alertas-inventario \
  --template-file infraestructura/template.yaml \
  --parameter-overrides CodigoBucket=$BUCKET CodigoKey=codigo/manual.jar
```

## Probar la función

**1. Invocación directa con el evento de ejemplo:**
```bash
aws lambda invoke --function-name fn-alertas-inventario \
  --payload fileb://eventos/evento-prueba.json salida.json
cat salida.json      # {"batchItemFailures":[]}
```

**2. Enviando un mensaje a la cola (flujo productor → cola → consumidor):**
```bash
URL=$(aws cloudformation describe-stacks --stack-name fn-alertas-inventario \
  --query "Stacks[0].Outputs[?OutputKey=='ColaAlertasUrl'].OutputValue" --output text)
aws sqs send-message --queue-url $URL \
  --message-body '{"productoId":1,"stockActual":3,"stockMinimo":5,"fecha":"2026-11-21T10:00:00"}'
```

**3. Ver el resultado en CloudWatch Logs:**
```bash
aws logs tail /aws/lambda/fn-alertas-inventario --since 10m
```
Debe aparecer: `ALERTA STOCK BAJO | producto=1 | stockActual=3 | stockMinimo=5 | ... | reponer=7 unidades`.

**4. Probar la DLQ:** enviar un mensaje inválido (por ejemplo `--message-body 'hola'`). Tras 3 intentos aparece en `alertas-fallidas` y la alarma pasa a estado `ALARM`.

**5. Flujo completo desde el sistema:** registrar una venta en el API Gateway que deje el stock de un producto en su mínimo. ms-inventario publica el mensaje y la función lo procesa (ver paso 3).

## Integración con ms-inventario

La URL de la cola (salida `ColaAlertasUrl` del stack) se entrega a ms-inventario mediante la variable de entorno `SQS_ALERTAS_URL`. El pipeline del repositorio `infraestructura-swarm` la obtiene automáticamente de este stack al desplegar.
