package today.scrappy.cadeteria;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Le avisa al cadete cuando la oficina le contesta un pedido.
 *
 * <p><b>Por qué está adentro de esta app y no se usa la app de ntfy.</b> El
 * backend publica el aviso a un topic de ntfy, y lo obvio sería que el cadete
 * instale la app de ntfy y se suscriba. Pero eso son dos apps para instalar y
 * configurar en cada teléfono, y una suscripción que alguien puede borrar o
 * silenciar sin que nadie se entere. Escuchar el topic desde acá es el mismo
 * protocolo (HTTP, una línea de JSON por mensaje) y no necesita ninguna
 * dependencia: {@code HttpURLConnection} de la plataforma alcanza.
 *
 * <p><b>Por qué en primer plano.</b> Desde Android 8 un servicio común se muere
 * a los minutos, y el aviso tiene que llegar con el teléfono en el bolsillo. La
 * notificación permanente es el precio, y va en un canal de importancia mínima
 * para que quede plegada y muda: es un cartel de "estoy escuchando", no un
 * aviso.
 *
 * <p><b>El topic lo da el backend</b> ({@code /cadeteria/mi-topic/}), con la
 * misma cookie de sesión del WebView. No hay ninguna credencial adentro del
 * APK, que es obligatorio porque el APK se publica en GitHub Releases. Sin
 * sesión todavía, el servicio espera y vuelve a preguntar: el cadete puede
 * abrir la app por primera vez y loguearse después.
 */
public class AvisosService extends Service {

    private static final String TAG = "AvisosService";
    private static final String CANAL_ESTADO = "avisos_estado";
    private static final String CANAL_AVISOS = "avisos";
    private static final int NOTIFICACION = 2;

    /**
     * Cuánto espera antes de reconectar. Corto a propósito: el caso normal es
     * que el stream se corte (cambio de red, timeout) sin que nada esté mal, y
     * cada segundo de espera es un segundo en el que el aviso no llega.
     */
    private static final long REINTENTO_MS = 5 * 1000L;
    /** Y cuánto espera si todavía no hay sesión (o el cadete no tiene topic). */
    private static final long SIN_SESION_MS = 5 * 60 * 1000L;

    private static final String PREFS = "avisos";
    /** Id del último mensaje de ntfy ya notificado, para no repetirlo. */
    private static final String ULTIMO = "ultimo_id";

    private volatile boolean vivo = true;
    private Thread oyente;

    public static void arrancar(Context contexto) {
        try {
            contexto.startForegroundService(new Intent(contexto, AvisosService.class));
        } catch (Exception e) {
            // Android puede negarse a arrancar un servicio en primer plano si la
            // app no está visible (restricciones de fondo). No es grave: la
            // próxima vez que el cadete abra la app se intenta de nuevo.
            Log.w(TAG, "no se pudo arrancar el servicio de avisos", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanales();
        startForeground(NOTIFICACION, notificacionDeEstado());
        oyente = new Thread(new Runnable() {
            @Override
            public void run() {
                escuchar();
            }
        });
        oyente.setDaemon(true);
        oyente.start();
    }

    /**
     * El bucle: consigue el topic y se queda colgado del stream de ntfy.
     *
     * <p>ntfy mantiene la conexión abierta y va escribiendo una línea de JSON
     * por mensaje. Cuando se corta (señal, cambio de red, timeout del server) se
     * vuelve a conectar con {@code since=} del último id visto, así que los
     * avisos que llegaron mientras estaba desconectado no se pierden.
     */
    private void escuchar() {
        String topic = null;
        while (vivo) {
            if (topic == null) {
                topic = pedirTopic();
                if (topic == null) {
                    // Sin sesión, o el cadete no tiene topic cargado en el admin.
                    // No es un error a reportar: se espera y se vuelve a mirar.
                    dormir(SIN_SESION_MS);
                    continue;
                }
            }
            HttpURLConnection con = null;
            try {
                // Primer arranque: `1m` y NO `all`. ntfy.sh cachea 12 h, y `all`
                // le plantaria al cadete medio dia de avisos viejos de golpe la
                // primera vez que abre la app.
                String desde = prefs().getString(ULTIMO, "1m");
                URL url = new URL("https://ntfy.sh/" + topic + "/json?since=" + desde);
                con = (HttpURLConnection) url.openConnection();
                con.setConnectTimeout(20000);
                // 2 minutos y no 0 (infinito): ntfy manda un keepalive cada ~45 s,
                // asi que en una conexion sana el timeout NUNCA salta. Cuando
                // salta es que la red se murio en silencio -- y con timeout
                // infinito el readLine quedaria colgado para siempre y el cadete
                // dejaria de recibir avisos sin que nada falle a la vista.
                con.setReadTimeout(120000);
                if (con.getResponseCode() != 200) {
                    Log.w(TAG, "ntfy respondió " + con.getResponseCode());
                    if (con.getResponseCode() == 404 || con.getResponseCode() == 403) {
                        // El topic no sirve: volver a preguntárselo al backend
                        // (pudo cambiar, o el cadete quedó sin topic).
                        topic = null;
                    }
                    dormir(REINTENTO_MS);
                    continue;
                }
                BufferedReader lector = new BufferedReader(new InputStreamReader(
                        con.getInputStream(), StandardCharsets.UTF_8));
                Log.i(TAG, "escuchando ntfy desde " + desde);
                String linea;
                while (vivo && (linea = lector.readLine()) != null) {
                    if (!linea.trim().isEmpty()) {
                        procesar(linea);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "se cortó la escucha de avisos", e);
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
            if (vivo) {
                dormir(REINTENTO_MS);
            }
        }
    }

    /** El topic de este cadete, o {@code null} si todavía no se puede saber. */
    private String pedirTopic() {
        HttpURLConnection con = null;
        try {
            String base = BuildConfig.BASE_URL;
            String cookies = CookieManager.getInstance().getCookie(base);
            if (cookies == null || !cookies.contains("sessionid")) {
                Log.i(TAG, "sin sesion todavia: no se pide el topic");
                return null;  // todavía no entró, o la sesión venció
            }
            URL url = new URL(base + "/cadeteria/mi-topic/");
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Cookie", cookies);
            if (con.getResponseCode() != 200) {
                Log.i(TAG, "mi-topic respondio " + con.getResponseCode());
                return null;
            }
            String cuerpo = leerTodo(con);
            String topic = valorJson(cuerpo, "topic");
            if (topic == null || topic.isEmpty()) {
                Log.i(TAG, "este cadete no tiene topic cargado");
                return null;
            }
            Log.i(TAG, "topic obtenido");
            return topic;
        } catch (Exception e) {
            Log.w(TAG, "no se pudo preguntar el topic", e);
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    /** Una línea del stream de ntfy: si es un mensaje, lo notifica. */
    private void procesar(String linea) {
        String tipo = valorJson(linea, "event");
        String id = valorJson(linea, "id");
        if (!"message".equals(tipo) || id == null) {
            // `open` y `keepalive` también vienen por el stream.
            return;
        }
        if (id.equals(prefs().getString(ULTIMO, null))) {
            return;  // re-entrega del mismo mensaje
        }
        prefs().edit().putString(ULTIMO, id).apply();
        Log.i(TAG, "aviso recibido " + id);

        String titulo = valorJson(linea, "title");
        String cuerpo = valorJson(linea, "message");
        if (cuerpo == null) {
            cuerpo = "";
        }
        // El backend manda el link al chat al final del cuerpo. Se saca de ahí
        // para que tocar la notificación abra ESE pedido, y no la pantalla del
        // día: el cadete está esperando una respuesta puntual.
        String destino = primeraUrl(cuerpo);
        if (destino != null && destino.equals(MainActivity.chatEnPantalla)) {
            // El cadete está mirando ESE chat: el mensaje ya le aparece en la
            // pantalla por el polling. Una notificación además es ruido, y se
            // queda en la barra aunque ya la haya leído.
            Log.i(TAG, "aviso de un chat que está en pantalla: no se notifica");
            return;
        }
        notificar(titulo == null ? "Cadetería" : titulo,
                  destino == null ? cuerpo : cuerpo.replace(destino, "").trim(),
                  destino, idNotificacion(destino, id));
    }

    /**
     * Un id por CHAT, no por mensaje. Así tres respuestas al mismo pedido son una
     * sola notificación que se actualiza, y abrir ese chat la puede borrar
     * sabiendo cuál es (ver {@link MainActivity}). Con un id por mensaje se
     * apilaban y quedaban en la barra aunque el cadete ya estuviera leyendo.
     */
    static int idNotificacion(String destino, String idMensaje) {
        return destino != null ? destino.hashCode() : idMensaje.hashCode();
    }

    private void notificar(String titulo, String texto, String destino, int id) {
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (destino != null) {
            abrir.putExtra(MainActivity.EXTRA_URL, destino);
        }
        PendingIntent toque = PendingIntent.getActivity(
                this, id, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CANAL_AVISOS)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(toque)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(id, n);
        }
    }

    // ---------------------------------------------------------------- utilidades

    /**
     * El valor de una clave de string de un JSON, o {@code null}.
     *
     * <p>{@code org.json} de la plataforma y NO un parser a mano: la primera
     * versión buscaba el texto {@code "clave":"} y andaba con el JSON compacto de
     * ntfy, pero el {@code JsonResponse} de Django escribe {@code "clave": "valor"}
     * con un espacio, así que el topic nunca aparecía y el servicio se quedaba
     * esperando en silencio. Pasó en la primera prueba en el teléfono
     * (2026-09-25).
     */
    private static String valorJson(String json, String clave) {
        try {
            JSONObject o = new JSONObject(json);
            return o.isNull(clave) ? null : o.optString(clave, null);
        } catch (JSONException e) {
            return null;
        }
    }

    /** La primera URL https del texto, o {@code null}. */
    private static String primeraUrl(String texto) {
        int i = texto.indexOf("https://");
        if (i < 0) {
            return null;
        }
        int fin = i;
        while (fin < texto.length() && !Character.isWhitespace(texto.charAt(fin))) {
            fin++;
        }
        return texto.substring(i, fin);
    }

    private static String leerTodo(HttpURLConnection con) throws Exception {
        BufferedReader lector = new BufferedReader(new InputStreamReader(
                con.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder todo = new StringBuilder();
        String linea;
        while ((linea = lector.readLine()) != null) {
            todo.append(linea);
        }
        return todo.toString();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            vivo = false;
        }
    }

    private void crearCanales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        // El cartel de "estoy escuchando": importancia MÍNIMA para que quede
        // plegado y sin sonido. Es obligatorio (servicio en primer plano), no
        // es un aviso.
        NotificationChannel estado = new NotificationChannel(
                CANAL_ESTADO, "Avisos activos", NotificationManager.IMPORTANCE_MIN);
        estado.setDescription("Avisa que la app está esperando respuestas de "
                + "administración.");
        estado.setShowBadge(false);
        nm.createNotificationChannel(estado);

        NotificationChannel avisos = new NotificationChannel(
                CANAL_AVISOS, "Respuestas de administración",
                NotificationManager.IMPORTANCE_HIGH);
        avisos.setDescription("Cuando administración contesta un pedido tuyo.");
        nm.createNotificationChannel(avisos);
    }

    private Notification notificacionDeEstado() {
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new Notification.Builder(this, CANAL_ESTADO)
                .setContentTitle("Cadetería")
                .setContentText("Escuchando respuestas de administración")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentIntent(PendingIntent.getActivity(this, 0, abrir,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Que Android lo vuelva a levantar si lo mata por memoria.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        vivo = false;
        if (oyente != null) {
            oyente.interrupt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
