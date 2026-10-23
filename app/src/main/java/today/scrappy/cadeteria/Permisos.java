package today.scrappy.cadeteria;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.SparseArray;

/**
 * Pedir un permiso con el cartel "Permitir" de Android, sin salir de la app.
 *
 * <p>Hasta la 0.9 los botones "Arreglar" de Salud mandaban a los ajustes de la
 * app, donde hay que buscar Permisos → el permiso → Permitir: tres pantallas
 * afuera para algo que Android pregunta en un cartel (reportado por Federico
 * 2026-10-23). Ahora se pide con {@code requestPermissions}, y sólo se cae a
 * los ajustes cuando Android ya no deja preguntar.
 *
 * <p>Android no dice si mostró el cartel o si lo negó solo (después de dos "No"
 * no pregunta más y devuelve negado al instante). Se distingue por el tiempo:
 * una persona tarda más de medio segundo en leer y tocar "No permitir"; el
 * rechazo automático vuelve en unos pocos milisegundos. Y además
 * {@code shouldShowRequestPermissionRationale} tiene que estar en falso, que es
 * lo que Android responde cuando ya no va a preguntar. Guardar "ya lo pedimos"
 * no alcanza: un permiso "sólo esta vez" que venció vuelve a preguntarse.
 */
final class Permisos {

    /** Menos que esto entre pedir y la respuesta = Android no mostró el cartel. */
    private static final long SIN_CARTEL_MS = 500;

    private static final SparseArray<Long> pedidoEn = new SparseArray<>();

    private Permisos() { }

    static boolean concedidos(Activity a, String[] permisos) {
        for (String p : permisos) {
            if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** {@code requestPermissions} anotando cuándo, para {@link #sinCartel}. */
    static void pedir(Activity a, String[] permisos, int codigo) {
        pedidoEn.put(codigo, SystemClock.elapsedRealtime());
        a.requestPermissions(permisos, codigo);
    }

    static boolean algunoConcedido(int[] resultados) {
        for (int r : resultados) {
            if (r == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Si la respuesta negada vino sin que la persona viera el cartel: entonces
     * la única salida son los ajustes, y hay que decírselo.
     */
    static boolean sinCartel(Activity a, int codigo, String[] permisos, int[] resultados) {
        Long desde = pedidoEn.get(codigo);
        pedidoEn.remove(codigo);
        if (desde == null || algunoConcedido(resultados)) {
            return false;
        }
        if (SystemClock.elapsedRealtime() - desde > SIN_CARTEL_MS) {
            return false;
        }
        for (String p : permisos) {
            if (a.shouldShowRequestPermissionRationale(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * El cartel propio para cuando Android ya no pregunta: explica qué tocar y
     * abre los ajustes de la app recién si la persona dice que sí.
     */
    static void ofrecerAjustes(final Activity a, String que, final Intent ajustes) {
        if (a.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Falta un permiso")
                .setMessage("Android ya no deja pedir " + que + " desde la app. "
                        + "Tocá \"Abrir ajustes\", entrá a Permisos y elegí "
                        + "\"Permitir\". Después volvé con la flecha de atrás.")
                .setPositiveButton("Abrir ajustes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        abrir(a, ajustes);
                    }
                })
                .setNegativeButton("Ahora no", null)
                .show();
    }

    static Intent ajustesDeLaApp(Activity a) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.getPackageName()));
    }

    /** {@code startActivity} que cae a los ajustes de la app si el fabricante
     *  no tiene esa pantalla. */
    static void abrir(Activity a, Intent destino) {
        try {
            a.startActivity(destino != null ? destino : ajustesDeLaApp(a));
        } catch (Exception e) {
            try {
                a.startActivity(ajustesDeLaApp(a));
            } catch (Exception ignorada) {
                // Nada más que hacer.
            }
        }
    }
}
