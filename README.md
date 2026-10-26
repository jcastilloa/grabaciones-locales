# Grabaciones Locales

Aplicación Android nativa en Kotlin y Jetpack Compose para intentar grabar llamadas telefónicas automáticamente. Es una versión modificada (fork) de [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder), con una arquitectura ADB integrada adaptada de [CallVault](https://github.com/madkongo/CallVault). No requiere instalar Shizuku ni otra aplicación auxiliar.

> **Aviso:** Android, el fabricante, el operador y la ruta de audio pueden impedir la captura o silenciar el audio remoto. No se garantiza que se graben ambas voces ni que funcione con la pantalla bloqueada, Bluetooth, VoIP o un modelo concreto. Se reportó un emparejamiento aparentemente exitoso y una llamada de prueba en un Moto G85, pero no se confirmó que produjera una grabación. Haz y escucha pruebas en el dispositivo antes de confiar en las grabaciones.

## Funciones

- Controles separados para grabar automáticamente llamadas entrantes y salientes (desactivadas por defecto). La pantalla principal indica si no se grabará y por qué.
- Detección mediante `InCallService` o, como alternativa menos fiable, estado telefónico.
- Emparejamiento ADB inalámbrico desde el asistente: la app publica una notificación persistente antes de abrir los ajustes; el código se introduce exclusivamente con su acción `RemoteInput`, sin volver a la Activity. El descubrimiento mDNS permanece en primer plano. La app conserva su identidad y lanza un servicio `app_process` con UID shell para la captura; no se recurre silenciosamente al micrófono si falla el servicio.
- Supervisión del Binder durante una grabación, avisos cuando el servicio shell no inicia o se desconecta, y fallo explícito si Android mantiene silenciada la captura. Los errores se guardan en privado incluso con el registro detallado desactivado. La reconexión tras reinicios se intenta cuando Android y el fabricante lo permiten; no está garantizada.
- Conversión a **MP3 mono, 64 kbit/s, 16 kHz** mediante LAME.
- Historial local con reproducción, compartir y eliminación. Los MP3 se publican en `Music/GrabacionesLocales` (carpeta Música); Ajustes muestra la ruta y permite abrirla con el gestor de archivos. Los WAV temporales se guardan en el almacenamiento privado y se conservan si falla la conversión.
- Respaldo opcional y unidireccional a MEGA de nuevas grabaciones MP3, con soporte para 2FA, cola y reintentos. Los archivos locales no se borran ni se modifican.

La fuente recomendada intenta combinar subida y bajada de audio. En Ajustes también se puede escoger `VOICE_CALL`, pero el firmware puede omitir el audio remoto. La app no puede superar las restricciones del dispositivo.

## Instalación y emparejamiento ADB

La APK de distribución se encuentra en [`dist/GrabacionesLocales-1.0.3.apk`](dist/GrabacionesLocales-1.0.3.apk). También se puede instalar o actualizar con `adb install -r dist/GrabacionesLocales-1.0.3.apk`. Requiere Android 12 (API 31) o posterior.

1. Abre Grabaciones Locales, acepta el aviso y concede permiso de notificaciones. Pulsa **Emparejar**; la app inicia el servicio y publica su notificación persistente antes de abrir los ajustes de Depuración inalámbrica.
2. En Android, activa las **Opciones de desarrollador** si hace falta, activa **Depuración inalámbrica** y elige **Vincular dispositivo con código de vinculación**. El teléfono y la app deben estar en la misma red Wi-Fi solo durante el emparejamiento.
3. Cuando Android muestre el código, despliega las notificaciones, expande **Grabaciones Locales** y pulsa **Introducir código**. Escribe las seis cifras y envía la respuesta sin volver a la Activity; el servicio mantiene mDNS y comunica el resultado por notificación. El mensaje de éxito confirma el emparejamiento; la conexión del servicio de captura se inicia cuando la app la necesita. Si se deniega o bloquea el permiso de notificaciones, actívalo en los ajustes de la app y vuelve a iniciar el emparejamiento. No hace falta Shizuku, un ordenador ni otra app.
4. Concede el permiso de detección de llamadas que solicite el asistente. Al completar los requisitos esenciales, el asistente se cierra automáticamente; la exención de batería es recomendada, pero no bloquea la configuración.
5. En Ajustes, activa por separado la grabación entrante o saliente que quieras probar. Está desactivada por defecto. Haz llamadas de prueba; al final, el MP3 debería aparecer en el historial y en `Music/GrabacionesLocales`. Escucha ambas voces. Repite con la app en segundo plano y la pantalla bloqueada.

La identidad ADB se guarda en el almacenamiento privado de la app. Al conectar el servicio shell, la app intenta concederse `WRITE_SECURE_SETTINGS` para reactivar Depuración inalámbrica después de reiniciar; Android u otro fabricante pueden rechazarlo. La app no apaga Depuración inalámbrica mientras depende de ese transporte. Desactivarla o revocar el dispositivo emparejado puede detener la grabación; se puede volver a emparejar desde el asistente. Revisa la lista de dispositivos emparejados en Opciones de desarrollador y revoca el acceso cuando ya no quieras usarlo. Mantén protegida la depuración inalámbrica: ADB concede acceso potente al dispositivo.

En Ajustes puedes activar **Grabación sin Wi-Fi**. La app arma una vez el transporte `adb tcpip` local, protegido por la misma identidad RSA de ADB; después el grabador se controla por Binder y no consulta mDNS en cada llamada. El modo está desactivado por defecto porque abre una superficie de depuración local adicional. Tras un reinicio Android limpia el modo `tcpip`: vuelve a conectarte a Wi-Fi, abre la app y deja que lo rearme; no se debe prometer recuperación offline antes de esa inicialización. La optimización de batería y las restricciones de segundo plano siguen dependiendo del dispositivo. En Ajustes se puede exportar un informe diagnóstico; revisa su contenido antes de compartirlo.

## Respaldo MEGA

1. Abre **Ajustes → Respaldo MEGA** e inicia sesión con el correo y la contraseña de tu cuenta. Si tiene 2FA, introduce el código actual de tu autenticador.
2. Activa **Respaldo automático** para poner nuevas grabaciones completadas en la cola. Está desactivado por defecto. **Solo Wi-Fi** también está desactivado por defecto; actívalo si no quieres usar datos móviles. Las preferencias elegidas se conservan.
3. Los MP3 se suben a `/GrabacionesLocales/{marca-modelo}-{id}/`. La subida es unidireccional: no descarga ni sincroniza cambios de MEGA hacia el teléfono. Los MP3 locales permanecen intactos; ante errores, la entrada queda pendiente para reintentar.

No se necesita una App Key ni configurar `MEGA_APP_KEY`: con MEGA SDK v10.21.0 la app pasa `null` al constructor, según la [confirmación del mantenedor del SDK](https://github.com/meganz/sdk/issues/2706#issuecomment-2850231304). La contraseña no se guarda; el token de sesión se cifra con AES-GCM y Android Keystore. Consulta [`docs/MEGA_BACKUP.md`](docs/MEGA_BACKUP.md) para detalles y pruebas. El probe automatizado usa credenciales de prueba inválidas para verificar que la solicitud llega a la API; no es una autenticación real ni prueba una subida con una cuenta.

### Si no aparece historial ni MP3

Comprueba que la grabación automática esté activada para esa dirección y que la pantalla principal indique **Operativa**. Durante la llamada debería mostrarse la notificación **Grabación en curso**; al terminar, el historial se actualiza automáticamente y el MP3 queda en `Music/GrabacionesLocales`. Si hubo un fallo, revisa la notificación de error y exporta el informe desde Ajustes. Una grabación sin muestras no crea un MP3 vacío; si falla la conversión, el WAV queda en almacenamiento privado y la app informa del motivo.

## Limitaciones

- Aceptar una fuente de audio no demuestra que Android entregue ambas voces; algunos teléfonos solo dan micrófono o silencio.
- Un bloqueo parcial de CPU ayuda durante una sesión, pero no garantiza audio con la pantalla apagada.
- Llamadas en espera, conferencias y cambios entre llamadas simultáneas pueden mezclarse o confundirse.
- `InCallService` puede empezar después del inicio de la llamada. La detección por estado telefónico puede retrasarse o generar eventos falsos.
- No se garantiza la captura de llamadas VoIP, Wi-Fi Calling, Bluetooth ni aplicaciones de terceros. La disponibilidad del número depende de Android y del operador.
- Un emulador verifica el codificador y las funciones locales, no la captura telefónica real.

Respeta las leyes locales y obtén el consentimiento que corresponda antes de grabar. La aplicación no informa automáticamente a la otra persona.

## Compilación y pruebas

Requisitos: Android SDK 37, NDK 28.2.13676358, CMake 3.22.1 y JDK 17 (el wrapper fija Gradle 9.8.2).

```sh
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleRelease
```

La release de Gradle se genera inicialmente como APK sin firmar. La APK incluida aquí está firmada con la clave de depuración local del mantenedor, no con una clave de publicación; solo podrá actualizar instalaciones firmadas con el mismo certificado. Pasaron 22 pruebas unitarias y 11 instrumentales en un emulador Pixel 7 API 36, incluyendo codificación MP3/MediaStore, ADB, inicialización JNI de MEGA con App Key `null`, persistencia de sesión y conservación del texto/cursor al mostrar u ocultar la contraseña. Un probe optativo con credenciales deliberadamente inválidas recibió un rechazo de autenticación de la API de MEGA: confirma que la solicitud llega al servicio, pero no prueba el acceso a una cuenta real ni una subida. Una prueba manual adicional completó el emparejamiento desde el diálogo de Depuración inalámbrica usando RemoteInput, también con la APK release minificada. Un smoke test separado confirmó el daemon shell y la entrega del Binder. Las pruebas actuales no validan el audio de llamadas reales, segundo plano durante una llamada ni pantalla bloqueada en un teléfono. Se reportó una llamada de prueba en un Moto G85, pero no se pudo confirmar un MP3 o una entrada de historial; ese teléfono no estaba conectado a este entorno. El emparejamiento en el emulador no demuestra la captura telefónica ni la compatibilidad con Motorola. Véase [`docs/PRUEBAS_DISPOSITIVO_FISICO.md`](docs/PRUEBAS_DISPOSITIVO_FISICO.md) para el protocolo pendiente.

## Licencias y procedencia

- El código reutilizado de ShizuCallRecorder conserva sus avisos. Este fork está sujeto a la **GPL-3.0-or-later y a los términos adicionales de la sección 7** en [`LICENSE`](LICENSE). La app identifica visiblemente el fork y enlaza el repositorio original.
- La arquitectura ADB y partes del código de emparejamiento están adaptadas de CallVault, licenciado bajo GPL-3.0-or-later con los mismos términos adicionales; la interfaz enlaza su repositorio. El texto de licencia de CallVault es idéntico al de este proyecto.
- La detección mDNS deriva de código de Shizuku bajo Apache-2.0; se incluye [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt). `libadb-android`, Conscrypt y BouncyCastle tienen sus propias licencias; véanse [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) y los metadatos de las dependencias.
- LAME 3.100 se incluye como biblioteca compartida bajo LGPL-2.0-or-later. Su licencia está en [`licenses/LAME-LGPL-2.0.txt`](licenses/LAME-LGPL-2.0.txt) y dentro de la app.
- MEGA SDK v10.21.0 y sus bibliotecas JNI se distribuyen con sus avisos BSD-2-Clause y licencias de dependencias en [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), `licenses/` y los recursos de la app.
