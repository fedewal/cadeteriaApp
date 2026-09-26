package today.scrappy.cadeteria;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * El caparazón: muestra la pantalla del cadete, que vive en Django.
 *
 * <p>No hay pantallas nativas a propósito. Un cambio en la pantalla del día
 * le llega al cadete recargando, sin publicar un APK nuevo. Lo que la app
 * aporta hoy es el ícono en el launcher, arrancar sin la barra del navegador
 * y avisar cuando hay una versión nueva.
 *
 * <p>Permisos sensibles: la CÁMARA, para escanear la etiqueta del equipo al
 * cerrar un evento (se pide cuando la pantalla la usa, no al instalar), y la
 * UBICACIÓN, para el mapa del centro de mando ({@link UbicacionService}, el
 * mismo seguimiento que vendedoresApp). La ubicación se pide al abrir,
 * encadenada con las notificaciones: ubicación → notificaciones → segundo
 * plano.
 */
public class MainActivity extends Activity {

    private static final String INICIO = BuildConfig.BASE_URL + "/cadeteria/";

    /** `--azul` de las plantillas de /cadeteria/. Si cambia allá, cambia acá. */
    private static final int AZUL = 0xFF242F62;

    /** Código propio para la respuesta de {@link #onRequestPermissionsResult}. */
    private static final int PIDO_CAMARA = 1;
    private static final int PIDO_AVISOS = 2;
    private static final int PIDO_UBICACION = 3;
    private static final int PIDO_SEGUNDO_PLANO = 4;

    /**
     * Con qué URL abrir, en vez del día. La pone la notificación de una
     * respuesta de administración: el cadete la toca porque quiere ver ESE
     * pedido, y caer en la pantalla del día lo obligaría a buscarlo.
     */
    public static final String EXTRA_URL = "url";

    /**
     * La URL del chat que el cadete tiene en pantalla AHORA, o {@code null}.
     *
     * <p>La lee {@link AvisosService} para no notificar un mensaje que el cadete
     * ya está viendo. Estático porque el servicio y la actividad viven en el
     * mismo proceso y no hay nada más que compartir; {@code volatile} porque el
     * servicio lo lee desde su propio hilo.
     */
    static volatile String chatEnPantalla;

    private WebView web;

    /**
     * El pedido de la página que quedó esperando a que Android conteste.
     *
     * <p>Son DOS permisos distintos y hay que atravesar los dos: el del sistema
     * (Android le pregunta al usuario por la cámara) y el del WebView (la página
     * pide `getUserMedia`). Si se contesta el del WebView sin tener el del
     * sistema, la cámara falla igual y sin explicación.
     */
    private PermissionRequest camaraPendiente;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // La barra de estado en el azul de la cabecera (`--azul` de las
        // plantillas). Sin esto queda en el gris del tema de Android: una banda
        // que no es de la app arriba de todo. El `theme-color` de la página NO
        // alcanza — el WebView lo ignora para la barra del sistema. Los iconos
        // quedan blancos solos (el tema no pide barra clara).
        getWindow().setStatusBarColor(AZUL);

        web = new WebView(this);
        setContentView(web);

        WebSettings ajustes = web.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);
        // La pantalla es celular-primero y trae su propio viewport: que el
        // WebView no la reescale como si fuera una página de escritorio.
        ajustes.setUseWideViewPort(false);
        ajustes.setLoadWithOverviewMode(false);
        ajustes.setSupportZoom(false);

        // La app le dice su versión al servidor en cada request. Sirve para dos
        // cosas: que la pantalla pueda MOSTRAR qué versión está instalada (desde
        // adentro de un WebView no hay otra forma de saberlo), y para que algún
        // día el servidor pueda rechazar una versión vieja sin depender de que
        // el teléfono le pregunte a GitHub.
        ajustes.setUserAgentString(
                ajustes.getUserAgentString() + " CadeteriaApp/" + BuildConfig.VERSION_NAME);

        // La sesión de Django vive en una cookie y tiene que sobrevivir a
        // cerrar la app: si no, el cadete loguea cada vez que la abre.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        // La cámara del escáner de series. `WebChromeClient` es el único lugar
        // donde el WebView pregunta esto: sin esta clase puesta, un
        // `getUserMedia` se rechaza en silencio y la página no puede distinguir
        // "el usuario dijo que no" de "esta app nunca lo va a permitir".
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest pedido) {
                if (!quiereLaCamara(pedido)) {
                    // Micrófono y todo lo demás se rechaza. La app no tiene por
                    // qué poder escuchar, y conceder de más es exactamente lo
                    // que hace que un permiso deje de significar algo.
                    pedido.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    concederCamara(pedido);
                    return;
                }
                camaraPendiente = pedido;
                requestPermissions(new String[]{Manifest.permission.CAMERA},
                        PIDO_CAMARA);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest pedido) {
                camaraPendiente = null;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri destino = req.getUrl();
                String esquema = destino.getScheme();
                // `tel:`, `whatsapp:` y `mailto:` no son páginas. Van al
                // sistema, o el día que la pantalla tenga un botón de llamar
                // al cliente no haría nada.
                if (esquema != null && !esquema.equals("http") && !esquema.equals("https")) {
                    abrirAfuera(destino);
                    return true;
                }
                // Cualquier host que no sea el nuestro tampoco: que un link
                // externo no se coma la app y deje al cadete sin vuelta.
                String host = destino.getHost();
                if (host != null && !BuildConfig.BASE_URL.contains(host)) {
                    abrirAfuera(destino);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                marcarChatEnPantalla(url);
                // El admin de Django es SÓLO la puerta de login (la pantalla
                // usa sesión y redirige ahí). Si el cadete queda en el índice
                // del admin se encierra: esa página no linkea a /cadeteria/ y
                // la app no tiene barra ni botón de inicio. Encontrado
                // probando vendedoresApp v0.1.0 -- acá se evita de entrada.
                if (url != null && url.startsWith(BuildConfig.BASE_URL + "/admin/")
                        && !url.contains("/admin/login")) {
                    v.loadUrl(INICIO);
                }
            }
        });

        String destino = urlDelIntent(getIntent());
        if (destino != null) {
            web.loadUrl(destino);
        } else if (savedInstanceState == null) {
            web.loadUrl(INICIO);
        } else {
            web.restoreState(savedInstanceState);
        }

        // Ubicación, avisos de administración y segundo plano, en cadena: Android
        // muestra un diálogo por vez. Se piden acá y no al instalar porque
        // Android los exige en tiempo de ejecución; si se niega alguno la app
        // sigue funcionando entera (sin mapa, o el cadete se entera de las
        // respuestas entrando a sus pedidos).
        pedirPermisos();

        // Al abrir, y en segundo plano: si hay una versión nueva publicada el
        // cadete se entera solo, sin que nadie tenga que avisarle.
        Actualizaciones.chequear(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Volver al frente también chequea: una app que quedó abierta de fondo
        // no se enteraría nunca de que hay versión nueva, y la actualización es
        // obligatoria. `Actualizaciones` trae su propio freno para no gastar la
        // cuota de la API de GitHub.
        Actualizaciones.chequearSiCorresponde(this);
        marcarChatEnPantalla(web.getUrl());
        // Y se vuelve a intentar arrancar la escucha de avisos: Android pudo
        // matar el servicio por memoria, o negarse a arrancarlo la primera vez
        // (la app todavía no estaba visible). Arrancarlo dos veces no hace nada.
        AvisosService.arrancar(this);
        // Lo mismo con la ubicación, que además puede haberse concedido
        // recién desde los ajustes. Sin permiso `arrancar` no hace nada.
        UbicacionService.arrancar(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Con la app en segundo plano el chat ya no está a la vista: los
        // mensajes nuevos tienen que volver a notificarse.
        chatEnPantalla = null;
    }

    /**
     * Si la URL es un chat de reclamo, lo marca como a la vista y le borra la
     * notificación: el cadete ya está leyendo esa conversación.
     */
    private void marcarChatEnPantalla(String url) {
        if (url == null || !url.contains("/cadeteria/reclamo/")
                || !url.contains("/chat/")) {
            chatEnPantalla = null;
            return;
        }
        int corte = url.indexOf('?');
        String chat = corte >= 0 ? url.substring(0, corte) : url;
        chatEnPantalla = chat;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(AvisosService.idNotificacion(chat, ""));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tocar el ícono con la app ya abierta tiene que llevar al día. Con
        // `launchMode="singleTask"` Android reanuda esta actividad sin pasar
        // por `onCreate`, así que si no se hace acá no se hace nunca.
        String destino = urlDelIntent(intent);
        if (destino != null) {
            // Vino de una notificación: va al pedido, no al día.
            web.loadUrl(destino);
            return;
        }
        String actual = web.getUrl();
        if (actual == null || !actual.startsWith(INICIO)) {
            web.loadUrl(INICIO);
        }
    }

    /** Si el pedido de la página incluye vídeo. El audio no se concede nunca. */
    private static boolean quiereLaCamara(PermissionRequest pedido) {
        String[] recursos = pedido.getResources();
        if (recursos == null) {
            return false;
        }
        for (String recurso : recursos) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(recurso)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Concede SÓLO el vídeo, aunque la página haya pedido más.
     *
     * <p>`grant()` con la lista que vino del pedido concedería también el
     * micrófono si la página lo hubiera incluido.
     */
    private static void concederCamara(PermissionRequest pedido) {
        pedido.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos,
                                           int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        if (codigo == PIDO_UBICACION) {
            // Concedida o no, se sigue con las notificaciones: negar la
            // ubicación no tiene por qué dejar al cadete sin avisos.
            pedirAvisos();
            return;
        }
        if (codigo == PIDO_AVISOS) {
            // Concedido o no, la escucha arranca: sin permiso no va a poder
            // mostrar la notificación, pero el permiso se puede dar después
            // desde los ajustes y entonces ya está escuchando.
            pedirSegundoPlanoYArrancar();
            return;
        }
        if (codigo == PIDO_SEGUNDO_PLANO) {
            // Con o sin "todo el tiempo": sin él sigue registrando mientras
            // la app está abierta.
            UbicacionService.arrancar(this);
            return;
        }
        if (codigo != PIDO_CAMARA) {
            return;
        }
        PermissionRequest pedido = camaraPendiente;
        camaraPendiente = null;
        if (pedido == null) {
            return;
        }
        boolean concedido = resultados.length > 0
                && resultados[0] == PackageManager.PERMISSION_GRANTED;
        if (concedido) {
            concederCamara(pedido);
        } else {
            // Se contesta que NO en vez de dejarlo colgado: la página tiene un
            // camino para cuando no hay cámara (tipear la serie), y sólo lo toma
            // si `getUserMedia` falla.
            pedido.deny();
        }
    }

    /**
     * La URL que trae el intent de una notificación, si es del sitio.
     *
     * <p>Se valida el prefijo a propósito: un intent puede venir de cualquier
     * app del teléfono, y cargar una URL ajena adentro de un WebView con la
     * sesión del cadete puesta es exactamente cómo se roba una sesión.
     */
    private static String urlDelIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || !url.startsWith(BuildConfig.BASE_URL + "/cadeteria/")) {
            return null;
        }
        return url;
    }

    /** Primer eslabón: la ubicación en primer plano. */
    private void pedirPermisos() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION},
                    PIDO_UBICACION);
            return;
        }
        pedirAvisos();
    }

    private void pedirAvisos() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                               PIDO_AVISOS);
            // El servicio arranca igual cuando conteste (o en el próximo
            // `onResume`): escucha aunque no pueda mostrar la notificación del
            // aviso, y así el cartel de estado no depende de la respuesta.
            return;
        }
        pedirSegundoPlanoYArrancar();
    }

    /**
     * Último eslabón: la ubicación "todo el tiempo", y arrancar los dos
     * servicios. Va DESPUÉS de la de primer plano porque Android rechaza el
     * pedido si vienen juntas (y desde Android 11 ni muestra diálogo: manda a
     * los ajustes).
     */
    private void pedirSegundoPlanoYArrancar() {
        AvisosService.arrancar(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    PIDO_SEGUNDO_PLANO);
        }
        // Arranca ya con el permiso de primer plano; el seguimiento con la
        // pantalla apagada empieza cuando se conceda "todo el tiempo".
        UbicacionService.arrancar(this);
    }

    private void abrirAfuera(Uri destino) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, destino));
        } catch (Exception e) {
            // Sin app que lo atienda no se hace nada: no vale tirar la
            // pantalla abajo por un link.
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        web.saveState(estado);
    }

    @Override
    public void onBackPressed() {
        // Atrás navega dentro del sitio; sólo cierra la app cuando ya no hay
        // a dónde volver.
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
