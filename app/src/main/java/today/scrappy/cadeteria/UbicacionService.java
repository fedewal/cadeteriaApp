package today.scrappy.cadeteria;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manda dónde está el cadete mientras trabaja, para el mapa del centro de
 * mando (hito 2). Es copia del de vendedoresApp: el mismo código de
 * seguimiento en las dos apps, cambian sólo la ruta, el Referer y el texto.
 *
 * <p>Es un servicio APARTE de {@link AvisosService}: ese es {@code dataSync} y
 * éste {@code location}, cada uno con su canal. Juntos, apagar uno (o que
 * Android le niegue el permiso a uno) se llevaría puesto al otro.
 *
 * <p>Es un servicio en PRIMER PLANO con notificación permanente. No es una
 * elección de diseño: desde Android 8 un servicio común se muere a los
 * minutos, y desde Android 10 la ubicación en segundo plano sólo llega a un
 * servicio de tipo {@code location}. La notificación además es lo correcto:
 * el cadete tiene que ver que lo están ubicando.
 *
 * <p>Se autentica con la MISMA cookie de sesión que el WebView: no hay
 * credencial adentro del APK, que es obligatorio si el repo es público.
 *
 * <p>Toma un punto cada 10 s pero los manda en tanda una vez por minuto
 * ({@code puntos} = JSON con lat, lon, precision y t en milisegundos). Si
 * la tanda no llega (sin señal, 302 por sesión vencida, servidor caído),
 * los puntos vuelven al buffer y salen con la tanda siguiente: el servidor
 * descarta los repetidos por la hora en que se tomaron, así que reenviar
 * no duplica nada.
 */
public class UbicacionService extends Service {

    private static final String TAG = "UbicacionService";
    private static final String CANAL = "ubicacion";
    private static final int NOTIFICACION = 1;

    private static final String RUTA = "/cadeteria/ubicacion/";
    private static final String REFERER = "/cadeteria/";

    /** Cada cuánto se pide una posición nueva: 10 s lo pidió Federico, para
     *  que la línea del mapa siga las calles y no corte esquinas. */
    private static final long CADA_MS = 10_000L;
    /** Cero a propósito: parado también cuenta. Es lo que permite que el
     *  mapa diga "de 10:05 a 10:35" en el mismo lugar. */
    private static final float MINIMO_METROS = 0f;
    /** Cada cuánto se sube el buffer: una conexión por minuto en vez de seis
     *  cuida la batería (la radio se despierta una vez y no seis). */
    private static final long ENVIO_MS = 60_000L;
    /** 600 puntos = 100 minutos sin señal. Pasado eso se tiran los más
     *  viejos: el buffer no crece sin techo en un teléfono que no vuelve a
     *  tener red. */
    private static final int MAX_PENDIENTES = 600;
    /** Un punto de red dentro de los 20 s de uno de GPS se ignora: la red
     *  salta 100 m y le mete dientes a una línea que el GPS dibuja bien.
     *  Pasados 20 s sin GPS (adentro de un local) la red vuelve a valer. */
    private static final long GPS_MANDA_MS = 20_000L;

    private LocationManager gestor;
    private LocationListener oyente;
    /** Siempre bajo su propio candado: lo tocan el main looper y el hilo
     *  del envío. */
    private final ArrayList<JSONObject> pendientes = new ArrayList<>();
    private long ultimoGps = 0L;
    private final Handler reloj = new Handler(Looper.getMainLooper());
    private final Runnable cadaMinuto = new Runnable() {
        @Override
        public void run() {
            enviarTanda();
            reloj.postDelayed(this, ENVIO_MS);
        }
    };

    public static void arrancar(Context contexto) {
        // Sin el permiso ni se intenta: con targetSdk 34, un servicio de tipo
        // `location` que llama a startForeground sin permiso de ubicación
        // revienta con SecurityException (y al arrancar el teléfono, con un
        // cartel de "la app se detuvo" que nadie entiende).
        if (contexto.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            contexto.startForegroundService(new Intent(contexto, UbicacionService.class));
        } catch (Exception e) {
            // Android puede negarse a arrancarlo con la app en segundo plano.
            // La próxima vez que se abra la app se vuelve a intentar.
            Log.w(TAG, "no se pudo arrancar el seguimiento", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanal();
        try {
            startForeground(NOTIFICACION, notificacion());
        } catch (SecurityException e) {
            // Con targetSdk 34, un servicio `location` arrancado sin la app a
            // la vista (al reiniciar el teléfono, o un reinicio de START_STICKY)
            // necesita "todo el tiempo"; con "mientras se usa" esto revienta
            // y Android muestra "la app se detuvo". Se baja callado y vuelve
            // a arrancar la próxima vez que se abra la app.
            Log.w(TAG, "sin permiso para seguir en segundo plano", e);
            stopSelf();
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Sin permiso no hay nada que hacer; la Activity lo vuelve a
            // pedir y a arrancar el servicio cuando lo tenga.
            stopSelf();
            return;
        }

        gestor = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        oyente = new LocationListener() {
            @Override
            public void onLocationChanged(Location punto) {
                anotar(punto);
            }

            // En API 26 estos tres siguen siendo abstractos en algunos
            // fabricantes: se implementan vacíos para no reventar ahí.
            @Override
            public void onProviderEnabled(String proveedor) { }

            @Override
            public void onProviderDisabled(String proveedor) { }
        };

        pedirA(LocationManager.GPS_PROVIDER);
        // También por red: adentro de un local el GPS no engancha, y una
        // posición aproximada es mejor que ninguna.
        pedirA(LocationManager.NETWORK_PROVIDER);
        reloj.postDelayed(cadaMinuto, ENVIO_MS);
    }

    private void pedirA(String proveedor) {
        try {
            if (gestor.isProviderEnabled(proveedor)) {
                gestor.requestLocationUpdates(proveedor, CADA_MS, MINIMO_METROS, oyente);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "no se pudo escuchar " + proveedor, e);
        }
    }

    private void anotar(Location punto) {
        // Llega en el main looper (requestLocationUpdates sin looper usa el
        // del hilo que lo pidió), así que ultimoGps no necesita candado.
        if (LocationManager.GPS_PROVIDER.equals(punto.getProvider())) {
            ultimoGps = punto.getTime();
        } else if (LocationManager.NETWORK_PROVIDER.equals(punto.getProvider())
                && punto.getTime() - ultimoGps < GPS_MANDA_MS) {
            return;
        }
        try {
            JSONObject p = new JSONObject();
            p.put("lat", punto.getLatitude());
            p.put("lon", punto.getLongitude());
            p.put("precision", Math.round(punto.getAccuracy()));
            // La hora en que el teléfono TOMÓ el punto, no la del envío: la
            // tanda sale hasta un minuto después (o cien, sin señal).
            p.put("t", punto.getTime());
            devolver(Collections.singletonList(p), false);
        } catch (Exception e) {
            // put() sólo falla con NaN/Infinity: ese punto no sirve.
            Log.w(TAG, "punto inválido", e);
        }
    }

    /** Agrega al buffer (al principio si son reintentos, para no desordenar
     *  la línea) y recorta los más viejos pasado el techo. */
    private void devolver(List<JSONObject> puntos, boolean alPrincipio) {
        synchronized (pendientes) {
            pendientes.addAll(alPrincipio ? 0 : pendientes.size(), puntos);
            int sobran = pendientes.size() - MAX_PENDIENTES;
            if (sobran > 0) {
                pendientes.subList(0, sobran).clear();
            }
        }
    }

    private void enviarTanda() {
        final List<JSONObject> tanda;
        synchronized (pendientes) {
            if (pendientes.isEmpty()) {
                return;
            }
            tanda = new ArrayList<>(pendientes);
            pendientes.clear();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection con = null;
                boolean llego = false;
                try {
                    String base = BuildConfig.BASE_URL;
                    String cookies = CookieManager.getInstance().getCookie(base);
                    if (cookies == null || !cookies.contains("sessionid")) {
                        // Todavía no entró nunca, o salió. La tanda se tira a
                        // propósito: guardarla sería acumular ubicaciones de
                        // alguien sin sesión. (Una sesión VENCIDA sí manda
                        // la cookie y vuelve 302: esa tanda espera, abajo.)
                        llego = true;
                        return;
                    }
                    URL url = new URL(base + RUTA);
                    con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("POST");
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(15000);
                    con.setDoOutput(true);
                    con.setInstanceFollowRedirects(false);
                    con.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded; charset=UTF-8");
                    con.setRequestProperty("Cookie", cookies);
                    String csrf = leerCookie(cookies, "csrftoken");
                    if (csrf != null) {
                        con.setRequestProperty("X-CSRFToken", csrf);
                        // Django compara el Referer en HTTPS.
                        con.setRequestProperty("Referer", base + REFERER);
                    }

                    String cuerpo = "puntos=" + URLEncoder.encode(
                            new JSONArray(tanda).toString(), "UTF-8");
                    try (OutputStream salida = con.getOutputStream()) {
                        salida.write(cuerpo.getBytes(StandardCharsets.UTF_8));
                    }
                    int codigo = con.getResponseCode();
                    // 302 = sesión vencida y 5xx = servidor caído: la tanda
                    // espera (hasta el techo del buffer). Otro 4xx (un 403 a
                    // quien entró pero no es vendedor/cadete activo) no se
                    // arregla reintentando: se tira, o serían 600 puntos
                    // reenviados cada minuto para siempre.
                    llego = codigo == 204 || (codigo >= 400 && codigo < 500);
                    if (codigo != 204) {
                        Log.w(TAG, "el servidor no tomó la tanda: " + codigo
                                + (llego ? " (se descarta)" : " (se reintenta)"));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "no se pudo mandar la tanda", e);
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                    if (!llego) {
                        devolver(tanda, true);
                    }
                }
            }
        }).start();
    }

    private static String leerCookie(String cookies, String nombre) {
        for (String parte : cookies.split(";")) {
            String limpia = parte.trim();
            if (limpia.startsWith(nombre + "=")) {
                return limpia.substring(nombre.length() + 1);
            }
        }
        return null;
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel canal = new NotificationChannel(
                    CANAL, "Ubicación", NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Avisa que la app está registrando dónde estás.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(canal);
            }
        }
    }

    private Notification notificacion() {
        return new Notification.Builder(this, CANAL)
                .setContentTitle("Cadetería")
                .setContentText("Registrando tu ubicación mientras trabajás")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
        reloj.removeCallbacks(cadaMinuto);
        if (gestor != null && oyente != null) {
            try {
                gestor.removeUpdates(oyente);
            } catch (SecurityException e) {
                Log.w(TAG, "no se pudo soltar el listener", e);
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
