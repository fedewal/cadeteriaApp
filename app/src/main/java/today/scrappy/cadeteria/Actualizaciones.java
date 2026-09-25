package today.scrappy.cadeteria;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

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
 * <p><b>La descarga la hace la app, no el navegador.</b> Antes abría la URL
 * con {@code ACTION_VIEW} y Chrome se encargaba; medido el 2026-09-25, Chrome
 * DESCARTA los {@code .apk}: la barra se llenaba al instante y el archivo no
 * aparecía nunca en Descargas. Del lado del servidor no faltaba nada (200,
 * {@code Content-Length} correcto, MD5 idéntico al publicado), así que el
 * problema era depender del navegador. Con la actualización obligatoria eso pasa
 * de molesto a grave: el cartel no se cierra y el cadete quedaría encerrado sin
 * forma de salir.
 *
 * <p>Se usa {@link DownloadManager} y su propio {@code content://} para
 * entregarle el archivo al instalador. NO hace falta {@code FileProvider} —que
 * vendría de androidx, y este proyecto no tiene dependencias a propósito—
 * porque {@code getUriForDownloadedFile} ya devuelve una URI que se puede
 * conceder. Y se baja a la carpeta privada de la app
 * ({@code setDestinationInExternalFilesDir}), que no pide permiso de
 * almacenamiento en ninguna versión.
 *
 * <p><b>La actualización es OBLIGATORIA</b> (pedido de Federico, 2026-09-25):
 * el cartel no se puede cerrar y no deja usar la app hasta actualizar. El
 * motivo es que una versión vieja puede no poder cumplir una regla que el
 * servidor ya exige -- el caso concreto es el escaneo de la serie, que la
 * v0.1.0 no puede hacer porque no declara el permiso de cámara.
 *
 * <p><b>Pero sólo bloquea cuando SABEMOS que hay una versión nueva.</b> Falla
 * en silencio, y eso ahora importa más que antes: sin señal, con GitHub caído,
 * con un JSON raro o sin APK en el release, el cadete pasa de largo. Bloquear
 * ante la duda lo dejaría encerrado fuera de la app parado frente a un cliente,
 * por una falla que no es suya y que no puede resolver.
 */
final class Actualizaciones {

    private static final String TAG = "Actualizaciones";
    private static final String API =
            "https://api.github.com/repos/fedewal/cadeteriaApp/releases/latest";

    /** Cuánto se espera antes de volver a preguntarle a GitHub. */
    private static final long ESPERA_MS = 15 * 60 * 1000L;

    /**
     * Ya hay un cartel puesto.
     *
     * <p>El cartel no se cierra nunca, así que sin esta guarda cada vuelta al
     * frente apilaría otro encima: el cadete tendría que cerrar N carteles que
     * no se pueden cerrar.
     */
    private static boolean mostrando = false;

    private static long ultimoChequeo = 0L;

    private Actualizaciones() { }

    /**
     * Chequea al volver al frente, no sólo al abrir.
     *
     * <p>Sin esto, una app que quedó abierta de fondo no se entera nunca de que
     * hay versión nueva, y "obligatoria" pasa a depender de que el cadete la
     * cierre y la abra.
     *
     * <p>Con freno de {@link #ESPERA_MS}: la API de GitHub sin token permite 60
     * llamadas por hora POR IP, y varios cadetes pueden salir por la misma IP del
     * operador. Pasarse no rompe nada (el chequeo falla y se pasa de largo), pero
     * es justo el momento en que el bloqueo dejaría de funcionar.
     */
    static void chequearSiCorresponde(final Activity actividad) {
        long ahora = System.currentTimeMillis();
        if (mostrando || ahora - ultimoChequeo < ESPERA_MS) {
            return;
        }
        chequear(actividad);
    }

    static void chequear(final Activity actividad) {
        ultimoChequeo = System.currentTimeMillis();
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
                            if (actividad.isFinishing() || mostrando) {
                                return;
                            }
                            mostrando = true;
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

    /**
     * El cartel que no se puede cerrar.
     *
     * <p>Sin "Ahora no", sin cerrar tocando afuera ({@code setCancelable(false)})
     * y sin que el botón de atrás lo saque: los tres son la misma salida y dejar
     * una sola vuelve opcional lo que no lo es.
     *
     * <p>El botón NO cierra el diálogo. Abre la descarga y el cartel queda
     * puesto, porque bajar el APK no es haberlo instalado: si se cerrara acá, el
     * cadete volvería a una app vieja que ya no debería usar. Cuando instale, el
     * proceso se reemplaza y el cartel desaparece con él.
     */
    private static void ofrecer(final Activity actividad, String version, final String apk) {
        AlertDialog cartel = new AlertDialog.Builder(actividad)
                .setTitle("Hay que actualizar")
                .setMessage("Versión " + version + " disponible. Se instala encima "
                        + "de la que tenés, sin perder la sesión. "
                        + "Esta versión ya no se puede usar.")
                .setCancelable(false)
                .setPositiveButton("Actualizar", null)
                .create();
        cartel.setCanceledOnTouchOutside(false);
        cartel.setOnKeyListener((dialogo, tecla, evento) ->
                tecla == KeyEvent.KEYCODE_BACK);
        cartel.show();
        // El listener se pone DESPUÉS de `show()` a propósito: puesto en el
        // builder, Android cierra el diálogo solo al tocar el botón, y acá no
        // queremos que se cierre.
        cartel.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                v -> bajarEInstalar(actividad, apk));
    }

    /**
     * Baja el APK con {@link DownloadManager} y abre el instalador al terminar.
     *
     * <p>Si algo de esto falla se cae al navegador, que es lo que había antes:
     * anda mal, pero es mejor que un botón que no hace nada.
     */
    private static void bajarEInstalar(final Activity actividad, final String apk) {
        try {
            final DownloadManager gestor = (DownloadManager)
                    actividad.getSystemService(Context.DOWNLOAD_SERVICE);
            if (gestor == null) {
                alNavegador(actividad, apk);
                return;
            }
            DownloadManager.Request pedido =
                    new DownloadManager.Request(Uri.parse(apk));
            pedido.setTitle("Actualizacion de Cadeteria");
            pedido.setMimeType("application/vnd.android.package-archive");
            pedido.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // Carpeta privada de la app: no pide permiso de almacenamiento en
            // ninguna version, y el instalador la puede leer igual porque la URI
            // se le concede.
            pedido.setDestinationInExternalFilesDir(
                    actividad, Environment.DIRECTORY_DOWNLOADS, "cadeteria.apk");

            final long id = gestor.enqueue(pedido);
            Toast.makeText(actividad, "Descargando la actualizacion...",
                    Toast.LENGTH_SHORT).show();
            escucharFin(actividad, gestor, id);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo bajar el APK", e);
            alNavegador(actividad, apk);
        }
    }

    private static void escucharFin(final Activity actividad,
                                    final DownloadManager gestor, final long id) {
        BroadcastReceiver aviso = new BroadcastReceiver() {
            @Override
            public void onReceive(Context contexto, Intent intent) {
                if (id != intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1)) {
                    return;
                }
                try {
                    contexto.unregisterReceiver(this);
                } catch (Exception e) {
                    Log.w(TAG, "receiver ya dado de baja", e);
                }
                Uri archivo = gestor.getUriForDownloadedFile(id);
                if (archivo == null) {
                    // Falló. Se DICE: con el cartel puesto y sin aviso, el cadete
                    // toca el botón para siempre sin entender por qué no pasa
                    // nada. Eso es justo lo que acaba de pasar con Chrome.
                    Toast.makeText(actividad,
                            "No se pudo descargar. Probá de nuevo con señal.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                instalar(actividad, archivo);
            }
        };
        IntentFilter filtro =
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // `RECEIVER_EXPORTED` es obligatorio desde Android 14 para un broadcast
        // del sistema; sin esto la app se cae al registrar.
        actividad.registerReceiver(aviso, filtro, Context.RECEIVER_EXPORTED);
    }

    private static void instalar(Activity actividad, Uri archivo) {
        try {
            Intent instalador = new Intent(Intent.ACTION_VIEW);
            instalador.setDataAndType(
                    archivo, "application/vnd.android.package-archive");
            // Sin este flag el instalador no puede leer el archivo: la URI es
            // nuestra y hay que prestársela explícitamente.
            instalador.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            actividad.startActivity(instalador);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo abrir el instalador", e);
            Toast.makeText(actividad,
                    "Se descargó, pero no se pudo abrir el instalador.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Lo de antes, sólo como último recurso. */
    private static void alNavegador(Activity actividad, String apk) {
        try {
            actividad.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apk)));
        } catch (Exception e) {
            Log.w(TAG, "no se pudo abrir la descarga", e);
        }
    }
}
