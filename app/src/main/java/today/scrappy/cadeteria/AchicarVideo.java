package today.scrappy.cadeteria;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Achica en el teléfono el video que se grabó o se eligió, antes de dárselo a
 * la página para que lo suba.
 *
 * <p>Un minuto filmado con la cámara del teléfono ronda los 100 MB: no pasa el
 * tope de 30 MB por pedido de nginx, y subirlo entero por datos móviles es
 * justo lo que no queremos. A 720 p de lado corto y 2 Mb/s, un minuto queda
 * en unos 15 MB más el audio, debajo de los 20 MB que pide el diseño.
 *
 * <p>El cartel no tiene "Cancelar", así que nada puede dejar al cadete
 * encerrado: la exportación la corta Transformer si en 10 s no escribe nada,
 * y todo el trámite tiene un tope de {@link #TIEMPO_MAXIMO_MS} (leer la
 * duración de un video que está en la nube espera a que se baje entero, y ahí
 * Transformer no mira). Las dos salidas entregan el original.
 *
 * <p><b>Todo lo de Media3 vive acá, y sólo acá.</b> La actualización es
 * obligatoria y salió para todos sin probarse antes en un teléfono: si algo de
 * Media3 tirara la app al abrir, el actualizador nunca podría entregar el
 * arreglo. Por eso esta clase recién se carga cuando se elige un video, nada
 * de esto corre al abrir la app, y CUALQUIER falla al achicar entrega el video
 * original: la página decide por su tope de 28 MB, que es lo que pasaba antes.
 *
 * <p>Sin el {@code OptIn} la release no compila: casi toda la API de
 * Transformer está marcada {@code @UnstableApi} y {@code lintVitalRelease}
 * corta el build.
 */
@OptIn(markerClass = UnstableApi.class)
final class AchicarVideo {

    /** La respuesta, siempre UNA sola vez y en el hilo principal. */
    interface Listo {
        void entregar(Uri video);

        void rechazar(String mensaje);
    }

    private static final String TAG = "AchicarVideo";

    /**
     * La cámara corta sola a los 60 s ({@code EXTRA_DURATION_LIMIT}), pero el
     * archivo puede decir un poco más (audio e imagen no terminan justo
     * juntos): los 2 s de margen evitan rechazar un video que la cámara ya
     * cortó.
     */
    private static final long MAXIMO_MS = 62_000;
    private static final int LADO_CORTO = 720;
    private static final int BITRATE = 2_000_000;
    private static final long CADA_MS = 500;
    /** Un minuto de video se achica en bastante menos; esto es solo la salida de emergencia. */
    private static final long TIEMPO_MAXIMO_MS = 120_000;

    private final Activity actividad;
    private final Uri origen;
    private final Listo listo;
    private final Handler principal = new Handler(Looper.getMainLooper());
    private final ProgressHolder avance = new ProgressHolder();
    private AlertDialog dialogo;
    private Transformer transformer;
    /** Ya se contestó: un error tardío de Transformer no puede contestar dos veces. */
    private boolean contestado;

    private final Runnable mostrarAvance = new Runnable() {
        @Override
        public void run() {
            if (contestado || transformer == null) {
                return;
            }
            try {
                if (transformer.getProgress(avance) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    dialogo.setMessage(texto(avance.progress));
                }
                principal.postDelayed(this, CADA_MS);
            } catch (Throwable t) {
                // El porcentaje es una cortesía: si no se puede leer, el
                // diálogo se queda en el último número y el video se termina igual.
                Log.w(TAG, "no se pudo leer el avance", t);
            }
        }
    };

    private final Runnable sePasoDelTiempo = new Runnable() {
        @Override
        public void run() {
            fallo(new TimeoutException("más de " + TIEMPO_MAXIMO_MS / 1000 + " s"));
        }
    };

    private AchicarVideo(Activity actividad, Uri origen, Listo listo) {
        this.actividad = actividad;
        this.origen = origen;
        this.listo = listo;
    }

    /** Llamar desde el hilo principal. No tira nunca: ante cualquier falla entrega {@code origen}. */
    static void achicar(Activity actividad, Uri origen, Listo listo) {
        new AchicarVideo(actividad, origen, listo).empezar();
    }

    private void empezar() {
        try {
            principal.postDelayed(sePasoDelTiempo, TIEMPO_MAXIMO_MS);
            // No se puede cancelar: la página está esperando el archivo y,
            // para un minuto de video, la espera es corta (ver arriba).
            dialogo = new AlertDialog.Builder(actividad)
                    .setMessage(texto(0))
                    .setCancelable(false)
                    .create();
            dialogo.show();
            // La duración se lee en otro hilo: si el video viene de la nube
            // (Drive, Fotos), leerla lo baja entero, y en el hilo principal eso
            // cuelga la app.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final long ms = duracion();
                    principal.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                seguir(ms);
                            } catch (Throwable t) {
                                fallo(t);
                            }
                        }
                    });
                }
            }).start();
        } catch (Throwable t) {
            fallo(t);
        }
    }

    private void seguir(long ms) {
        if (contestado) {
            return;
        }
        if (ms > MAXIMO_MS) {
            long s = ms / 1000;
            terminar(null, String.format(Locale.ROOT, "El video dura %d:%02d. Máximo 1 minuto: "
                    + "grabalo de nuevo o recortalo en la galería.", s / 60, s % 60));
            return;
        }
        final File salida = archivoDeSalida();
        transformer = new Transformer.Builder(actividad)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(new DefaultEncoderFactory.Builder(actividad)
                        .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                                .setBitrate(BITRATE)
                                .build())
                        .build())
                .addListener(new Transformer.Listener() {
                    // Los dos los llama Transformer desde su propio manejo de
                    // eventos: una excepción que se escape acá adentro tira la app.
                    @Override
                    public void onCompleted(Composition composicion, ExportResult resultado) {
                        try {
                            terminar(Uri.fromFile(salida), null);
                        } catch (Throwable t) {
                            fallo(t);
                        }
                    }

                    @Override
                    public void onError(Composition composicion, ExportResult resultado,
                                        ExportException error) {
                        // Sin `cancel()`: Transformer ya terminó por su cuenta.
                        Log.w(TAG, "Transformer no pudo, va el original", error);
                        terminar(origen, null);
                    }
                })
                .build();
        EditedMediaItem video = new EditedMediaItem.Builder(MediaItem.fromUri(origen))
                .setEffects(new Effects(Collections.emptyList(),
                        Collections.singletonList(Presentation.createForShortSide(LADO_CORTO))))
                .build();
        transformer.start(video, salida.getAbsolutePath());
        principal.postDelayed(mostrarAvance, CADA_MS);
    }

    /**
     * Cuánto dura, o -1 si no se pudo leer. Sin duración se achica igual: si
     * fuera larga, la página la frena por el peso.
     */
    private long duracion() {
        // Corre en otro hilo: ahí una excepción que se escape tira la app
        // entera, así que hasta el constructor va adentro del `try`.
        MediaMetadataRetriever lector = null;
        try {
            lector = new MediaMetadataRetriever();
            lector.setDataSource(actividad, origen);
            String ms = lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms != null ? Long.parseLong(ms.trim()) : -1;
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo leer la duración", t);
            return -1;
        } finally {
            if (lector != null) {
                try {
                    lector.release();
                } catch (Throwable ignorada) {
                    // Nada más que hacer.
                }
            }
        }
    }

    /**
     * En el caché de la app, borrando antes los videos achicados que quedaron:
     * cuando se elige uno nuevo, el anterior ya se subió o se descartó.
     */
    private File archivoDeSalida() {
        File carpeta = new File(actividad.getCacheDir(), "videos");
        File[] viejos = carpeta.listFiles();
        if (viejos != null) {
            for (File viejo : viejos) {
                if (viejo.getName().endsWith(".mp4") && !viejo.delete()) {
                    Log.w(TAG, "no se pudo borrar " + viejo.getName());
                }
            }
        }
        if (!carpeta.isDirectory() && !carpeta.mkdirs()) {
            Log.w(TAG, "no se pudo crear " + carpeta);
        }
        return new File(carpeta, "cadeteria_" + System.currentTimeMillis() + ".mp4");
    }

    /** Una excepción nuestra: si Transformer quedó andando, se lo para. */
    private void fallo(Throwable t) {
        Log.w(TAG, "no se pudo achicar el video, va el original", t);
        if (transformer != null && !contestado) {
            try {
                transformer.cancel();
            } catch (Throwable ignorada) {
                // Ya estaba terminado.
            }
        }
        terminar(origen, null);
    }

    private void terminar(Uri video, String rechazo) {
        if (contestado) {
            return;
        }
        contestado = true;
        principal.removeCallbacks(mostrarAvance);
        principal.removeCallbacks(sePasoDelTiempo);
        try {
            if (dialogo != null) {
                dialogo.dismiss();
            }
        } catch (Throwable ignorada) {
            // La actividad ya no está en pantalla: el diálogo se fue con ella.
        }
        try {
            if (rechazo != null) {
                listo.rechazar(rechazo);
            } else {
                listo.entregar(video);
            }
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo entregar el video", t);
        }
    }

    private static String texto(int porcentaje) {
        return "Preparando el video… " + porcentaje + " %";
    }
}
