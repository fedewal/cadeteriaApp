package today.scrappy.cadeteria;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
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
 * <p>A diferencia de vendedoresApp, acá NO hay servicio de ubicación. El único
 * permiso sensible es la CÁMARA, y existe por una sola razón: escanear la
 * etiqueta del equipo al cerrar un evento. Se pide cuando la pantalla la usa, no
 * al instalar. Cuando exista el rastreo propio del recorrido, esa tarea agrega lo
 * que necesite.
 */
public class MainActivity extends Activity {

    private static final String INICIO = BuildConfig.BASE_URL + "/cadeteria/";

    /** Código propio para la respuesta de {@link #onRequestPermissionsResult}. */
    private static final int PIDO_CAMARA = 1;

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

        if (savedInstanceState == null) {
            web.loadUrl(INICIO);
        } else {
            web.restoreState(savedInstanceState);
        }

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
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tocar el ícono con la app ya abierta tiene que llevar al día. Con
        // `launchMode="singleTask"` Android reanuda esta actividad sin pasar
        // por `onCreate`, así que si no se hace acá no se hace nunca.
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
