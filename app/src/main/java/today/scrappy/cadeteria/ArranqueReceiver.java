package today.scrappy.cadeteria;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Vuelve a escuchar los avisos, y a registrar la ubicación, cuando se reinicia
 * el teléfono.
 *
 * <p>Sin esto, un teléfono que se reinicia a la mañana deja al cadete sin
 * avisos hasta que abre la app — y no se entera, porque no falla nada visible:
 * el pedido queda esperando una respuesta que nunca suena.
 */
public class ArranqueReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context contexto, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AvisosService.arrancar(contexto);
            // Al arrancar el teléfono la app no está a la vista, y Android sólo deja
            // correr el seguimiento con "todo el tiempo" (Android 10+). Con
            // "mientras se usa" se espera a que se abra la app.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    || contexto.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                UbicacionService.arrancar(contexto);
            }
        }
    }
}
