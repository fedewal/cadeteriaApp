package today.scrappy.cadeteria;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * La notificación fija que Android exige mientras la app trabaja con la pantalla
 * apagada. No se puede sacar: sin ella Android corta los servicios.
 *
 * <p>Lo que sí se puede es que moleste lo menos posible (pedido de Federico,
 * 2026-09-27: no quería ver todo el día "Registrando tu ubicación mientras
 * trabajás"). Por eso:
 * <ul>
 *   <li><b>Una sola por app</b>: todos los servicios hacen {@code startForeground}
 *       con el MISMO {@link #ID}, y Android muestra una. Si uno se baja, no la
 *       borra mientras otro la siga usando.</li>
 *   <li><b>Importancia mínima</b>: sin ícono en la barra de estado, plegada al
 *       fondo, en las silenciosas.</li>
 *   <li><b>Texto neutro</b>: el nombre de la app, nada más.</li>
 * </ul>
 *
 * <p>El canal tiene id NUEVO a propósito: Android no deja bajarle la importancia a
 * un canal que ya existe en el teléfono (queda la que eligió el usuario o la del
 * día que se creó). Los viejos se borran acá para que no queden en los ajustes.
 */
final class NotificacionFija {

    static final int ID = 7;
    private static final String CANAL = "app_activa";
    private static final String[] CANALES_VIEJOS = {"ubicacion", "avisos_estado"};

    private NotificacionFija() { }

    static Notification crear(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) {
            for (String viejo : CANALES_VIEJOS) {
                nm.deleteNotificationChannel(viejo);
            }
            NotificationChannel canal = new NotificationChannel(
                    CANAL, "App activa", NotificationManager.IMPORTANCE_MIN);
            canal.setDescription("Android la exige mientras la app trabaja con la "
                    + "pantalla apagada. Queda plegada y sin sonido.");
            canal.setShowBadge(false);
            nm.createNotificationChannel(canal);
        }
        Intent abrir = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new Notification.Builder(c, CANAL)
                .setContentTitle("Cadetería")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(PendingIntent.getActivity(c, 0, abrir,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
    }
}
