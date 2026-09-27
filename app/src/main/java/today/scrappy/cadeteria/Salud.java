package today.scrappy.cadeteria;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que tiene que estar bien en el teléfono para que la app funcione, y cómo
 * arreglar cada cosa.
 *
 * <p>Existe porque lo que rompe la ubicación no falla a la vista: con el ahorro
 * de batería prendido Android apaga el GPS con la pantalla, y el teléfono deja
 * de mandar puntos sin un solo error (medido en el teléfono de prueba el
 * 2026-09-27: los pedidos de GPS quedaban `inactive`). Lo mismo con la
 * ubicación apagada, el permiso en "mientras se usa" o la optimización de
 * batería de Samsung.
 *
 * <p>Lo usan tres lugares: {@link SaludActivity} (la sección), el cartel rojo
 * de {@link MainActivity} (sólo lo {@link Item#critico crítico}) y
 * {@link UbicacionService}, que manda {@link #paraElServidor} al centro de
 * mando para que el mapa diga POR QUÉ no llega la ubicación.
 *
 * <p>Las claves son las de {@code vendedores.services.ubicacion.CLAVES_SALUD}
 * en el backend: si se agrega una acá, se agrega allá o el servidor la tira.
 */
final class Salud {

    /** Un chequeo: si está bien, qué significa, y cómo arreglarlo. */
    static final class Item {
        final String clave;
        final String titulo;
        final boolean ok;
        final String detalle;
        /** Crítico = sin esto la ubicación no llega al centro de mando. */
        final boolean critico;
        final Intent arreglar;

        Item(String clave, String titulo, boolean ok, String detalle,
             boolean critico, Intent arreglar) {
            this.clave = clave;
            this.titulo = titulo;
            this.ok = ok;
            this.detalle = detalle;
            this.critico = critico;
            this.arreglar = arreglar;
        }
    }

    private Salud() { }

    static List<Item> revisar(Context c) {
        List<Item> items = new ArrayList<>();
        Intent ajustesApp = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + c.getPackageName()));

        boolean ubicacion = concedido(c, Manifest.permission.ACCESS_FINE_LOCATION);
        items.add(new Item("ubicacion", "Permiso de ubicación precisa", ubicacion,
                ubicacion ? "Concedido."
                        : "Sin esto la app no sabe dónde estás. En Permisos → Ubicación, "
                          + "elegí \"Permitir todo el tiempo\" y \"Ubicación precisa\".",
                true, ajustesApp));

        boolean segundoPlano = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || concedido(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        items.add(new Item("segundo_plano", "Ubicación \"todo el tiempo\"", segundoPlano,
                segundoPlano ? "Concedido."
                        : "Con \"mientras se usa la app\" la ubicación se corta cuando "
                          + "bloqueás el teléfono. En Permisos → Ubicación, elegí "
                          + "\"Permitir todo el tiempo\".",
                true, ajustesApp));

        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        boolean gps = lm != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || lm.isLocationEnabled())
                && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        items.add(new Item("gps", "Ubicación del teléfono prendida", gps,
                gps ? "Prendida."
                        : "La ubicación del teléfono está apagada: prendela desde el panel "
                          + "rápido o desde acá.",
                true, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));

        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean ahorro = pm == null || !pm.isPowerSaveMode()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && pm.getLocationPowerSaveMode() == PowerManager.LOCATION_MODE_NO_CHANGE);
        items.add(new Item("ahorro", "Ahorro de batería apagado", ahorro,
                ahorro ? "Apagado."
                        : "Con el ahorro de batería prendido, el GPS se apaga cuando "
                          + "bloqueás el teléfono y tu ubicación deja de llegar. Apagalo.",
                true, new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)));

        boolean bateria = pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
        items.add(new Item("bateria", "Sin límite de batería para la app", bateria,
                bateria ? "La app puede trabajar con el teléfono bloqueado."
                        : "Android puede dormir la app en segundo plano y cortar la "
                          + "ubicación y los avisos. Elegí \"Sin restricciones\".",
                true, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + c.getPackageName()))));

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        boolean avisos = nm == null || nm.areNotificationsEnabled();
        Intent ajustesAvisos = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        items.add(new Item("notificaciones", "Notificaciones", avisos,
                avisos ? "Activadas."
                        : "Sin notificaciones no te enterás cuando administración "
                          + "contesta un pedido.",
                false, ajustesAvisos));

        boolean camara = concedido(c, Manifest.permission.CAMERA);
        items.add(new Item("camara", "Cámara", camara,
                camara ? "Concedida."
                        : "Todavía no. Se pide la primera vez que escaneás una serie.",
                false, ajustesApp));

        boolean instalar = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || c.getPackageManager().canRequestPackageInstalls();
        items.add(new Item("actualizaciones", "Instalar actualizaciones", instalar,
                instalar ? "La app se puede actualizar sola."
                        : "Hace falta para instalar las versiones nuevas. Activá "
                          + "\"Permitir de esta fuente\".",
                false, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + c.getPackageName()))));

        long ultimo = UbicacionService.ultimoPuntoMs;
        long minutos = ultimo == 0 ? -1 : (System.currentTimeMillis() - ultimo) / 60_000L;
        // Sin clave: no es un permiso, es la prueba de que lo demás funciona.
        items.add(new Item(null, "Último punto de GPS enviado",
                minutos >= 0 && minutos <= 15,
                minutos < 0 ? "Todavía ninguno desde que se abrió la app. Adentro de un "
                              + "local es normal: el GPS necesita ver el cielo."
                        : minutos == 0 ? "Hace menos de un minuto."
                        : "Hace " + minutos + " min." + (minutos > 15
                              ? " Si estás afuera, revisá lo de arriba." : ""),
                false, null));
        return items;
    }

    /** Lo crítico que está mal, para el cartel rojo. Vacío si todo bien. */
    static List<Item> criticosFallando(Context c) {
        List<Item> mal = new ArrayList<>();
        for (Item i : revisar(c)) {
            if (i.critico && !i.ok) {
                mal.add(i);
            }
        }
        return mal;
    }

    /** `{clave: bool}` para el centro de mando. */
    static JSONObject paraElServidor(Context c) {
        JSONObject salud = new JSONObject();
        for (Item i : revisar(c)) {
            if (i.clave == null) {
                continue;
            }
            try {
                salud.put(i.clave, i.ok);
            } catch (Exception e) {
                // put() con clave no nula y boolean no falla.
            }
        }
        return salud;
    }

    private static boolean concedido(Context c, String permiso) {
        return c.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED;
    }
}
