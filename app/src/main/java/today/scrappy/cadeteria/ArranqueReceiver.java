package today.scrappy.cadeteria;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

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
            // Sólo arranca si el permiso de ubicación está dado: lo chequea
            // `arrancar`, porque sin él el servicio revienta.
            UbicacionService.arrancar(contexto);
        }
    }
}
