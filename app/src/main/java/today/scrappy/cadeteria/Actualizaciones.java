package today.scrappy.cadeteria;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Avisa cuando hay una versión nueva publicada.
 *
 * <p>Pregunta a la API de Releases de GitHub y no a un endpoint nuestro: el
 * release ya es la fuente de verdad, así que publicar una versión es el
 * único paso y no hay dos lugares que se puedan desincronizar. El repo es
 * público, así que no hace falta token.
 *
 * <p>No descarga ni instala sola: abre el APK en el navegador y el
 * instalador del sistema hace el resto. Instalar desde la app pediría
 * {@code REQUEST_INSTALL_PACKAGES} y una sesión de PackageInstaller, mucho
 * más código para ahorrar un toque.
 *
 * <p>Falla en silencio: sin señal, con GitHub caído o con un JSON que no
 * entendemos, no pasa nada. Es un aviso, no una función de la que dependa
 * el trabajo del cadete.
 */
final class Actualizaciones {

    private static final String TAG = "Actualizaciones";
    private static final String API =
            "https://api.github.com/repos/fedewal/cadeteriaApp/releases/latest";

    private Actualizaciones() { }

    static void chequear(final Activity actividad) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject release = traer();
                    if (release == null) {
                        return;
                    }
                    final String version = release.optString("tag_name", "")
                            .replaceFirst("^[vV]", "");
                    if (!esMasNueva(version, BuildConfig.VERSION_NAME)) {
                        return;
                    }
                    final String apk = urlDelApk(release);
                    if (apk == null) {
                        return;
                    }
                    actividad.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (actividad.isFinishing()) {
                                return;
                            }
                            ofrecer(actividad, version, apk);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "no se pudo chequear la versión", e);
                }
            }
        }).start();
    }

    private static JSONObject traer() throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
        try {
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            if (con.getResponseCode() != 200) {
                return null;
            }
            StringBuilder cuerpo = new StringBuilder();
            BufferedReader lector = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
            String linea;
            while ((linea = lector.readLine()) != null) {
                cuerpo.append(linea);
            }
            lector.close();
            return new JSONObject(cuerpo.toString());
        } finally {
            con.disconnect();
        }
    }

    private static String urlDelApk(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && a.optString("name", "").endsWith(".apk")) {
                String url = a.optString("browser_download_url", "");
                if (!url.isEmpty()) {
                    return url;
                }
            }
        }
        return null;
    }

    /**
     * Compara "0.1.2" contra "0.1.10" por número y no por texto.
     *
     * <p>Comparar las cadenas sueltas diría que 0.1.2 es más nueva que
     * 0.1.10, y además ofrecería "actualizar" a una versión vieja si
     * alguien borrara el último release -- una instalación que Android
     * rechaza y que dejaría al vendedor con un cartel que no se va.
     */
    static boolean esMasNueva(String candidata, String actual) {
        if (candidata == null || candidata.isEmpty()) {
            return false;
        }
        String[] a = candidata.split("\\.");
        String[] b = actual.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? aEntero(a[i]) : 0;
            int y = i < b.length ? aEntero(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int aEntero(String parte) {
        try {
            return Integer.parseInt(parte.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void ofrecer(final Activity actividad, String version, final String apk) {
        new AlertDialog.Builder(actividad)
                .setTitle("Hay una versión nueva")
                .setMessage("Versión " + version + " disponible. Se instala encima "
                        + "de la que tenés, sin perder la sesión.")
                .setPositiveButton("Actualizar", (dialogo, cual) -> {
                    try {
                        actividad.startActivity(
                                new Intent(Intent.ACTION_VIEW, Uri.parse(apk)));
                    } catch (Exception e) {
                        Log.w(TAG, "no se pudo abrir la descarga", e);
                    }
                })
                .setNegativeButton("Ahora no", null)
                .show();
    }
}
