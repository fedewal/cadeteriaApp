# Cadetería — app Android

Caparazón Android de la pantalla del cadete de ClearWater (`/cadeteria/` en el
backend de Scrappy).

**No es un port.** La pantalla vive en Django y se ve en un WebView: un cambio
le llega al cadete recargando, sin publicar un APK nuevo.

Lo que aporta sobre abrir la URL en Chrome: el ícono en el launcher, arrancar
sin la barra del navegador, avisar cuando hay una versión nueva, los avisos de
administración con el teléfono en el bolsillo y, desde la 0.7.0, **la ubicación
del cadete para el mapa del centro de mando** — el mismo seguimiento que
[vendedoresApp](https://github.com/fedewal/vendedoresApp).

La pantalla también se puede instalar **sin este APK**: Chrome en el teléfono,
entrar a `/cadeteria/` y "Agregar a pantalla de inicio". El APK existe para
distribuirla y actualizarla igual que la de vendedores.

## Instalación

Bajar el APK de [Releases](../../releases), abrirlo en el teléfono y aceptar
"instalar apps de orígenes desconocidos" cuando Android lo pida.

Después de instalar, **en cada teléfono**, o el seguimiento se corta con la
pantalla apagada:

1. **Ubicación → Permitir todo el tiempo.** La app la pide al abrir (primero
   "mientras se usa", después "todo el tiempo"); si Android manda a los ajustes:
   Ajustes → Aplicaciones → Cadetería → Permisos → Ubicación → "Permitir todo el
   tiempo".
2. **Batería sin restricciones** (Ajustes → Aplicaciones → Cadetería → Batería).
   One UI mata los servicios en segundo plano y el rastreo se corta a las pocas
   horas.
3. **Notificaciones permitidas**: los dos servicios en primer plano necesitan
   su notificación.

El cadete necesita **usuario y contraseña**. Los crea administración con:

```bash
python manage.py crear_cadetes <usuario> --instalador "<su nombre en el CRM>"
python manage.py crear_cadetes <usuario> --clave   # la imprime una sola vez
```

El nombre del instalador tiene que ser **exactamente** el que figura en el
campo `Instalador` de las coordinaciones del CRM, o el cadete no ve ninguna
visita.

## Qué hace

- **`MainActivity`** — el WebView. Mantiene la cookie de sesión entre aperturas
  (si no, habría que loguearse cada vez), manda los esquemas que no son páginas
  (`tel:`, `whatsapp:`) al sistema, deja que "atrás" navegue dentro del sitio, y
  si el login deja al cadete en el índice del admin de Django lo devuelve a la
  pantalla del día. Eso último no es teórico: pasó probando vendedoresApp
  v0.1.0, y sin barra de navegador la app queda encerrada.
- **`UbicacionService`** — servicio en primer plano (`location`) que toma **un
  punto cada 10 s, mandados en tanda cada minuto** a `POST /cadeteria/ubicacion/`
  (`puntos` = JSON con `lat`, `lon`, `precision` y `t`, la hora del teléfono en
  milisegundos). Parado también toma puntos: es lo que deja ver cuánto estuvo en
  cada lugar. Si la tanda no llega (sin señal, sesión vencida → 302), los puntos
  esperan a la siguiente, hasta 600 (100 minutos); el servidor descarta los
  repetidos. Es un servicio **aparte** de `AvisosService` (`dataSync`), con su
  propio canal de notificación: sin permiso de ubicación los avisos siguen
  andando. `ArranqueReceiver` levanta los dos al reiniciar el teléfono.
- **`Actualizaciones`** — pregunta a la API de Releases de GitHub al abrir. No
  descarga ni instala sola: abre el APK y el instalador del sistema hace el
  resto. Falla en silencio, porque es un aviso y no algo de lo que dependa el
  trabajo del cadete.

No hay ninguna credencial dentro del APK: la autenticación es la cookie de
sesión del propio WebView. Eso es lo que permite que este repo sea público.

## Compilar

Necesita el SDK de Android y un JDK 17+ (sirve el que trae Android Studio).

```bash
echo "sdk.dir=C:/ruta/al/Android/Sdk" > local.properties
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

`local.properties` va con **barras normales**: con `\` el formato .properties
las lee como escapes y el build muere en `java.io.IOException: Invalid file
path`, que no dice nada del problema real.

**Cero dependencias**, y es a propósito: con `minSdk 26` todo lo que usa la app
está en la plataforma. En vendedoresApp se probó con `appcompat` y arrastraba la
stdlib de Kotlin en dos versiones incompatibles (`Duplicate class kotlin.*`);
sacarla resolvió eso de raíz y deja el APK en 13 KB.

Para firmar una release hace falta un `keystore.properties` en la raíz (no está
en el repo):

```properties
storeFile=C:/ruta/al/cadeteria-release.jks
storePassword=...
keyAlias=cadeteria
keyPassword=...
```

Es un keystore **propio**: el de vendedoresApp no sirve, porque son dos
`applicationId` distintos y Android los trata como dos apps sin relación.

⚠ **Si se pierde el keystore, la app no se puede volver a actualizar nunca**:
Android rechaza una actualización firmada con otra clave y hay que desinstalar y
reinstalar en cada teléfono.

## Qué NO hace todavía

La pantalla es de **sólo lectura**: muestra las visitas del día leídas del CRM
en vivo. Mientras Creator siga siendo el sistema de registro, lo que el cadete
carga sigue yendo por ahí, y la propia pantalla lo dice.

Falta el resto del flujo del prototipo —entrar a la coordinación, el evento, los
repuestos, el cierre con firma— que es donde vive el verificador y necesita los
interruptores de escritura en el CRM.
