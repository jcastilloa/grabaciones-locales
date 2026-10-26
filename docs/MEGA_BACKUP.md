# Respaldo MEGA

En **Ajustes → Respaldo MEGA**, inicia sesión con correo y contraseña. Si la cuenta tiene 2FA, introduce el código actual de tu autenticador. Activa **Respaldo automático** para subir nuevas grabaciones; **Solo Wi-Fi** está desactivado por defecto y puede activarse para no usar datos móviles. La cola se conserva si falla la red, la sesión o el espacio disponible. Los MP3 locales nunca se eliminan.

Las grabaciones se guardan en `/GrabacionesLocales/{marca-modelo}-{id}/`. El identificador aleatorio se crea una vez por instalación.

## App Key

MEGA SDK v10.21.0 ya no requiere una App Key para usar la API. El parámetro se conserva por compatibilidad, pero la aplicación inicializa `MegaApiAndroid` pasándole `null` (no una cadena vacía). Esto está confirmado por un mantenedor del SDK en [meganz/sdk#2706](https://github.com/meganz/sdk/issues/2706#issuecomment-2850231304) y por la documentación del constructor C++.

No hay que configurar `MEGA_APP_KEY` para compilar ni iniciar sesión. La prueba instrumental automatizada de autenticación usa credenciales deliberadamente inválidas y confirma que la API responde; no es una autenticación de cuenta real ni prueba una subida. Para repetir esa comprobación optativa: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.jcastillo.grabaciones.integrations.mega.MegaApiAuthenticationProbeTest -Pandroid.testInstrumentationRunnerArguments.megaApiProbe=true`. La autenticación real y la transferencia con una cuenta MEGA siguen pendientes.

La sesión devuelta por `dumpSession()` se guarda cifrada con AES-GCM y Android Keystore y se recupera con `fastLogin()`; la contraseña no se conserva.
