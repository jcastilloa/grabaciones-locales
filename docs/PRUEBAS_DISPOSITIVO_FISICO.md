# Plan de validación en teléfono físico

**Estado:** pendiente. El usuario reportó que el emparejamiento parecía funcionar y realizó una llamada de prueba en un Moto G85, pero no encontró historial ni MP3 y no pudo confirmar una grabación. En la sesión de compilación solo estaba conectado un emulador Pixel 7 API 36; el teléfono físico no estaba conectado para revisar logs o audio.

La aceptación del emparejamiento ADB solo confirma la identidad ADB; no confirma que Android permita iniciar el detector, capturar audio o guardar un MP3. Completar este protocolo antes de afirmar compatibilidad con un dispositivo.

## Antes de probar

1. Actualiza la instalación existente con `adb install -r dist/GrabacionesLocales-1.0.0.apk`; no desinstales ni borres los datos, para conservar la identidad ADB privada. La APK está firmada con la clave debug local del mantenedor y solo actualizará instalaciones firmadas con esa misma clave.
2. Anota fabricante, modelo, versión de Android/API, operador, SIM usada, fuente de audio elegida y modo de detección. Mantén el permiso de notificaciones activo.
3. Abre Grabaciones Locales y espera a que la configuración esencial termine automáticamente. Si permanece en el asistente, usa el recuadro **Configuración pendiente** para identificar el permiso requerido; batería es opcional.
4. En la pantalla principal, confirma **Operativa** y lee la causa exacta si el estado es negativo. Empareja de nuevo solo si Android perdió la autorización. En Ajustes activa explícitamente la dirección de llamada que se va a probar.
5. No uses conversaciones sensibles: avisa a la otra persona y obtén el consentimiento exigido por la ley local.

## Casos de prueba

| Caso | Pasos | Resultado que debe observarse | Resultado físico |
|---|---|---|---|
| Saliente, app visible | Llama a un teléfono de prueba, habla durante al menos 30 s y cuelga. | Notificación «Grabación en curso»; tras colgar, archivo MP3 mono en historial y `Music/GrabacionesLocales` (carpeta Música). Reproduce y escucha voz local y remota. | Pendiente |
| Entrante, app visible | Repite con una llamada entrante contestada. | Igual que la prueba saliente; revisa que la dirección configurada permita grabarla. | Pendiente |
| App en segundo plano | Concede permisos y activa grabación; vuelve a Inicio, bloquea el teléfono y realiza las pruebas entrante y saliente. | Se detecta la llamada, aparece la notificación de captura y el MP3 se publica al finalizar. Anota cualquier restricción de batería/OEM. | Pendiente |
| Pantalla bloqueada | Repite manteniendo la pantalla apagada durante toda la llamada. | La captura y la finalización no se interrumpen; comprobar contenido, no solo existencia del archivo. | Pendiente |
| MP3 y acceso externo | Desde Ajustes pulsa **Abrir carpeta**; si el fabricante muestra el selector de documentos, abre `Music/GrabacionesLocales` (carpeta Música). Revisa el archivo desde Archivos y desde el historial. | Mismo MP3 accesible externamente e historial con fecha, duración, reproducción, compartir y eliminación. | Pendiente |
| Error de captura | Desactiva Depuración inalámbrica o revoca deliberadamente ADB antes de una prueba controlada. | Estado de disponibilidad con motivo y notificación de error; no debe crearse una grabación falsa ni cambiarse silenciosamente al micrófono. Empareja de nuevo al terminar. | Pendiente |
| Reinicio/reconexión | Reinicia el teléfono con Wi-Fi y Depuración inalámbrica configurados; abre la app y revisa estado antes de otra llamada. | Documentar si el fabricante conservó ADB, si el Binder shell reconectó y si se necesitó emparejar de nuevo. No se garantiza recuperación automática. | Pendiente |

Después de cada llamada, comprobar también que el tamaño y duración del MP3 son razonables y que las dos voces se oyen con volumen suficiente. Una cabecera MP3 válida o una entrada de historial no prueba que contenga voz audible.

## Si no aparece una grabación

1. Comprueba que la dirección correspondiente está activada en Ajustes y que la pantalla principal no muestra un motivo de indisponibilidad.
2. Revisa notificaciones de error y la carpeta `Music/GrabacionesLocales` (Música). La conversión fallida puede dejar el WAV en el almacenamiento privado de la app, no en la carpeta pública.
3. Desde **Ajustes > Diagnóstico**, exporta el informe después de reproducir el problema. El informe redacta números telefónicos por defecto, pero inspecciónalo antes de compartirlo.
4. Anota hora aproximada, versión de la app, Android/API, modo/fuente de captura, dirección de llamada, si la pantalla estaba bloqueada y si se escuchó cada voz. No publiques números ni audio privado.

## Registro de resultados

- Dispositivo / Android / API: pendiente
- Operador / tipo de llamada: pendiente
- Emparejamiento y reconexión ADB: pendiente
- Llamada saliente, voz local/remota: pendiente
- Llamada entrante, voz local/remota: pendiente
- Segundo plano: pendiente
- Pantalla bloqueada: pendiente
- MP3 en historial y `Music/GrabacionesLocales`: pendiente
- Reinicio y recuperación: pendiente
- Errores/notas: pendiente
