package today.scrappy.cadeteria;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
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
 * <p>A diferencia de vendedoresApp, acá NO hay servicio de ubicación ni
 * permisos que pedir: la pantalla del cadete hoy es de sólo lectura. Cuando
 * exista el rastreo propio del recorrido, esa tarea agrega lo que necesite.
 */
public class MainActivity extends Activity {

    private static final String INICIO = BuildConfig.BASE_URL + "/cadeteria/";

    private WebView web;

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

        // La sesión de Django vive en una cookie y tiene que sobrevivir a
        // cerrar la app: si no, el cadete loguea cada vez que la abre.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

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
