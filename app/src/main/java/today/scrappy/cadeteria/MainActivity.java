package today.scrappy.cadeteria;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.os.PowerManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * El caparazón: muestra la pantalla del cadete, que vive en Django.
 *
 * <p>No hay pantallas nativas a propósito. Un cambio en la pantalla del día
 * le llega al cadete recargando, sin publicar un APK nuevo. Lo que la app
 * aporta hoy es el ícono en el launcher, arrancar sin la barra del navegador
 * y avisar cuando hay una versión nueva.
 *
 * <p>Permisos sensibles: la CÁMARA, para escanear la etiqueta del equipo al
 * cerrar un evento, el MICRÓFONO, para las notas de voz del reporte de errores
 * (los dos se piden cuando la pantalla los usa, no al instalar), y la
 * UBICACIÓN, para el mapa del centro de mando ({@link UbicacionService}, el
 * mismo seguimiento que vendedoresApp). La ubicación se pide al abrir,
 * encadenada con las notificaciones: ubicación → notificaciones → segundo
 * plano.
 */
public class MainActivity extends Activity {

    private static final String INICIO = BuildConfig.BASE_URL + "/cadeteria/";

    /** `--azul` de las plantillas de /cadeteria/. Si cambia allá, cambia acá. */
    private static final int AZUL = 0xFF242F62;

    /** Código propio para la respuesta de {@link #onRequestPermissionsResult}. */
    private static final int PIDO_PAGINA = 1;   // cámara y/o micrófono de `getUserMedia`
    private static final int PIDO_AVISOS = 2;
    private static final int PIDO_UBICACION = 3;
    private static final int PIDO_SEGUNDO_PLANO = 4;
    private static final int PIDO_CAMARA_FOTO = 5;
    private static final int ELIJO_FOTO = 6;

    /**
     * El `<input type="file">` que espera una foto. Sin `onShowFileChooser` el
     * WebView no hace NADA al tocarlo (reportado 2026-10-05: "me pide foto
     * pero no me deja cargarla").
     */
    private ValueCallback<Uri[]> fotoPendiente;
    private WebChromeClient.FileChooserParams fotoParams;
    /** Dónde escribe la cámara la foto que se está sacando. */
    private Uri fotoSacada;

    /**
     * Con qué URL abrir, en vez del día. La pone la notificación de una
     * respuesta de administración: el cadete la toca porque quiere ver ESE
     * pedido, y caer en la pantalla del día lo obligaría a buscarlo.
     */
    public static final String EXTRA_URL = "url";

    /**
     * La URL del chat que el cadete tiene en pantalla AHORA, o {@code null}.
     *
     * <p>La lee {@link AvisosService} para no notificar un mensaje que el cadete
     * ya está viendo. Estático porque el servicio y la actividad viven en el
     * mismo proceso y no hay nada más que compartir; {@code volatile} porque el
     * servicio lo lee desde su propio hilo.
     */
    static volatile String chatEnPantalla;

    private WebView web;
    /** El cartel rojo de arriba (ver `revisarSalud`). */
    private TextView cartelSalud;

    /**
     * El pedido de la página que quedó esperando a que Android conteste.
     *
     * <p>Son DOS permisos distintos y hay que atravesar los dos: el del sistema
     * (Android le pregunta al usuario por la cámara o el micrófono) y el del
     * WebView (la página pide `getUserMedia`). Si se contesta el del WebView sin
     * tener el del sistema, falla igual y sin explicación. Hasta la 0.9 el
     * micrófono se rechazaba siempre: por eso las notas de voz del reporte de
     * errores no grababan (reportado por Federico 2026-10-23).
     */
    private PermissionRequest pedidoDeLaPagina;
    /** Los recursos de {@link #pedidoDeLaPagina} que la app está dispuesta a dar. */
    private String[] recursosPedidos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // La barra de estado en el azul de la cabecera (`--azul` de las
        // plantillas). Sin esto queda en el gris del tema de Android: una banda
        // que no es de la app arriba de todo. El `theme-color` de la página NO
        // alcanza — el WebView lo ignora para la barra del sistema. Los iconos
        // quedan blancos solos (el tema no pide barra clara).
        getWindow().setStatusBarColor(AZUL);

        web = new WebView(this);
        // El cartel rojo arriba de la página cuando algo impide que la
        // ubicación llegue (ver `revisarSalud`). Es nativo porque lo que revisa
        // sólo lo sabe el teléfono; lleva a la sección de Salud.
        cartelSalud = new TextView(this);
        cartelSalud.setBackgroundColor(0xFFD63C3C);
        cartelSalud.setTextColor(0xFFFFFFFF);
        cartelSalud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        cartelSalud.setPadding(pad, pad, pad, pad);
        cartelSalud.setVisibility(View.GONE);
        cartelSalud.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SaludActivity.class));
            }
        });
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.addView(cartelSalud);
        raiz.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);

        WebSettings ajustes = web.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);
        // La pantalla es celular-primero y trae su propio viewport: que el
        // WebView no la reescale como si fuera una página de escritorio.
        ajustes.setUseWideViewPort(false);
        ajustes.setLoadWithOverviewMode(false);
        ajustes.setSupportZoom(false);

        // La app le dice su versión al servidor en cada request. Sirve para dos
        // cosas: que la pantalla pueda MOSTRAR qué versión está instalada (desde
        // adentro de un WebView no hay otra forma de saberlo), y para que algún
        // día el servidor pueda rechazar una versión vieja sin depender de que
        // el teléfono le pregunte a GitHub.
        ajustes.setUserAgentString(
                ajustes.getUserAgentString() + " CadeteriaApp/" + BuildConfig.VERSION_NAME);

        // La sesión de Django vive en una cookie y tiene que sobrevivir a
        // cerrar la app: si no, el cadete loguea cada vez que la abre.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        // La cámara del escáner de series y el micrófono de las notas de voz.
        // `WebChromeClient` es el único lugar donde el WebView pregunta esto:
        // sin esta clase puesta, un `getUserMedia` se rechaza en silencio y la
        // página no puede distinguir "el usuario dijo que no" de "esta app
        // nunca lo va a permitir".
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest pedido) {
                String[] recursos = recursosQueSeDan(pedido);
                if (recursos.length == 0 || !esNuestra(pedido.getOrigin())) {
                    // Cualquier otro recurso, o una página que no es la
                    // nuestra, se rechaza: conceder de más es exactamente lo
                    // que hace que un permiso deje de significar algo.
                    pedido.deny();
                    return;
                }
                String[] faltan = permisosQueFaltan(recursos);
                if (faltan.length == 0) {
                    pedido.grant(recursos);
                    return;
                }
                if (pedidoDeLaPagina != null) {
                    // Uno anterior sin contestar: que la página no quede colgada.
                    pedidoDeLaPagina.deny();
                }
                pedidoDeLaPagina = pedido;
                recursosPedidos = recursos;
                Permisos.pedir(MainActivity.this, faltan, PIDO_PAGINA);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest pedido) {
                pedidoDeLaPagina = null;
                recursosPedidos = null;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fotoPendiente != null) {
                    // Un pedido anterior sin contestar deja el input trabado.
                    fotoPendiente.onReceiveValue(null);
                }
                fotoPendiente = callback;
                fotoParams = params;
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    elegirFoto(true);
                } else {
                    requestPermissions(new String[]{Manifest.permission.CAMERA},
                            PIDO_CAMARA_FOTO);
                }
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri destino = req.getUrl();
                String esquema = destino.getScheme();
                // `tel:`, `whatsapp:` y `mailto:` no son páginas. Van al
                // sistema, o el día que la pantalla tenga un botón de llamar
                // al cliente no haría nada.
                // `scrappy://salud`: el link de los menús web a la sección
                // nativa de Salud. Va antes que los otros esquemas o se iría
                // afuera como si fuera un `tel:`.
                if ("scrappy".equals(esquema) && "salud".equals(destino.getHost())) {
                    startActivity(new Intent(MainActivity.this, SaludActivity.class));
                    return true;
                }
                if (esquema != null && !esquema.equals("http") && !esquema.equals("https")) {
                    abrirAfuera(destino);
                    return true;
                }
                // Cualquier host que no sea el nuestro tampoco: que un link
                // externo no se coma la app y deje al cadete sin vuelta.
                String host = destino.getHost();
                if (host != null && !BuildConfig.BASE_URL.contains(host)) {
                    abrirAfuera(destino);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                marcarChatEnPantalla(url);
                // El admin de Django es SÓLO la puerta de login (la pantalla
                // usa sesión y redirige ahí). Si el cadete queda en el índice
                // del admin se encierra: esa página no linkea a /cadeteria/ y
                // la app no tiene barra ni botón de inicio. Encontrado
                // probando vendedoresApp v0.1.0 -- acá se evita de entrada.
                if (url != null && url.startsWith(BuildConfig.BASE_URL + "/admin/")
                        && !url.contains("/admin/login")) {
                    v.loadUrl(INICIO);
                }
            }
        });

        String destino = urlDelIntent(getIntent());
        if (destino != null) {
            web.loadUrl(destino);
        } else if (savedInstanceState == null) {
            web.loadUrl(INICIO);
        } else {
            web.restoreState(savedInstanceState);
        }

        // Ubicación, avisos de administración y segundo plano, en cadena: Android
        // muestra un diálogo por vez. Se piden acá y no al instalar porque
        // Android los exige en tiempo de ejecución; si se niega alguno la app
        // sigue funcionando entera (sin mapa, o el cadete se entera de las
        // respuestas entrando a sus pedidos).
        pedirPermisos();

        // Al abrir, y en segundo plano: si hay una versión nueva publicada el
        // cadete se entera solo, sin que nadie tenga que avisarle.
        Actualizaciones.chequear(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Volver al frente también chequea: una app que quedó abierta de fondo
        // no se enteraría nunca de que hay versión nueva, y la actualización es
        // obligatoria. `Actualizaciones` trae su propio freno para no gastar la
        // cuota de la API de GitHub.
        Actualizaciones.chequearSiCorresponde(this);
        marcarChatEnPantalla(web.getUrl());
        // Y se vuelve a intentar arrancar la escucha de avisos: Android pudo
        // matar el servicio por memoria, o negarse a arrancarlo la primera vez
        // (la app todavía no estaba visible). Arrancarlo dos veces no hace nada.
        AvisosService.arrancar(this);
        // Lo mismo con la ubicación, que además puede haberse concedido
        // recién desde los ajustes. Sin permiso `arrancar` no hace nada.
        UbicacionService.arrancar(this);
        revisarSalud();
    }

    /**
     * Muestra el cartel rojo si algo impide que la ubicación llegue, y lo
     * esconde si ya está todo bien. Se revisa en cada vuelta al frente: la
     * persona va a los ajustes, lo arregla, y al volver el cartel no está.
     */
    private void revisarSalud() {
        List<Salud.Item> mal;
        try {
            mal = Salud.criticosFallando(this);
        } catch (Exception e) {
            // El cartel es una ayuda: si revisar falla, la app sigue igual.
            cartelSalud.setVisibility(View.GONE);
            return;
        }
        if (mal.isEmpty()) {
            cartelSalud.setVisibility(View.GONE);
            return;
        }
        // Genérico a propósito: los títulos de los chequeos están en positivo
        // ("Ahorro de batería apagado") y citarlos acá diría lo contrario de lo
        // que pasa. El detalle está en la sección.
        cartelSalud.setText("⚠  Tu ubicación no llega al centro de mando ("
                + mal.size() + (mal.size() == 1 ? " cosa" : " cosas")
                + " para arreglar). Tocá acá.");
        cartelSalud.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Con la app en segundo plano el chat ya no está a la vista: los
        // mensajes nuevos tienen que volver a notificarse.
        chatEnPantalla = null;
    }

    /**
     * Si la URL es un chat de reclamo, lo marca como a la vista y le borra la
     * notificación: el cadete ya está leyendo esa conversación.
     */
    private void marcarChatEnPantalla(String url) {
        if (url == null || !url.contains("/cadeteria/reclamo/")
                || !url.contains("/chat/")) {
            chatEnPantalla = null;
            return;
        }
        int corte = url.indexOf('?');
        String chat = corte >= 0 ? url.substring(0, corte) : url;
        chatEnPantalla = chat;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(AvisosService.idNotificacion(chat, ""));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tocar el ícono con la app ya abierta tiene que llevar al día. Con
        // `launchMode="singleTask"` Android reanuda esta actividad sin pasar
        // por `onCreate`, así que si no se hace acá no se hace nunca.
        String destino = urlDelIntent(intent);
        if (destino != null) {
            // Vino de una notificación: va al pedido, no al día.
            web.loadUrl(destino);
            return;
        }
        String actual = web.getUrl();
        if (actual == null || !actual.startsWith(INICIO)) {
            web.loadUrl(INICIO);
        }
    }

    /**
     * De lo que pidió la página, lo que la app da: cámara y micrófono. Nunca
     * se concede la lista tal como vino (`grant(pedido.getResources())`): eso
     * daría también cualquier recurso nuevo que el WebView agregue mañana.
     */
    private static String[] recursosQueSeDan(PermissionRequest pedido) {
        List<String> dar = new ArrayList<>();
        String[] recursos = pedido.getResources();
        if (recursos != null) {
            for (String recurso : recursos) {
                if (permisoDe(recurso) != null && !dar.contains(recurso)) {
                    dar.add(recurso);
                }
            }
        }
        return dar.toArray(new String[0]);
    }

    /** El permiso de Android detrás de cada recurso del WebView. */
    private static String permisoDe(String recurso) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(recurso)) {
            return Manifest.permission.CAMERA;
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(recurso)) {
            return Manifest.permission.RECORD_AUDIO;
        }
        return null;
    }

    private String[] permisosQueFaltan(String[] recursos) {
        List<String> faltan = new ArrayList<>();
        for (String recurso : recursos) {
            String permiso = permisoDe(recurso);
            if (checkSelfPermission(permiso) != PackageManager.PERMISSION_GRANTED) {
                faltan.add(permiso);
            }
        }
        return faltan.toArray(new String[0]);
    }

    /**
     * Si el pedido viene de nuestro sitio. Se compara esquema y host, no un
     * prefijo: "https://back.scrappy.today.otro.com" empieza igual.
     */
    private static boolean esNuestra(Uri origen) {
        Uri nuestro = Uri.parse(BuildConfig.BASE_URL);
        return origen != null && nuestro.getHost() != null
                && nuestro.getHost().equalsIgnoreCase(origen.getHost())
                && nuestro.getScheme().equalsIgnoreCase(origen.getScheme());
    }

    /**
     * Abre la cámara (si la página pidió `capture`) o un selector con la
     * cámara y la galería. La cámara escribe en MediaStore porque la app no
     * tiene `FileProvider` (no usa androidx, ver build.gradle); desde Android
     * 10 eso no pide permiso de almacenamiento. En 8 y 9 queda sólo la galería.
     */
    private void elegirFoto(boolean hayCamara) {
        Intent camara = null;
        if (hayCamara && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues datos = new ContentValues();
            datos.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "cadeteria_" + System.currentTimeMillis() + ".jpg");
            datos.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            datos.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cadeteria");
            try {
                fotoSacada = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, datos);
            } catch (RuntimeException e) {
                fotoSacada = null;
            }
            if (fotoSacada != null) {
                camara = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT, fotoSacada)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
        Intent galeria = fotoParams != null ? fotoParams.createIntent()
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        Intent abrir;
        if (camara != null && fotoParams != null && fotoParams.isCaptureEnabled()) {
            abrir = camara;
        } else {
            abrir = Intent.createChooser(galeria, "Foto del equipo");
            if (camara != null) {
                abrir.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camara});
            }
        }
        try {
            startActivityForResult(abrir, ELIJO_FOTO);
        } catch (ActivityNotFoundException e) {
            contestarFoto(null);
        }
    }

    private void contestarFoto(Uri[] fotos) {
        if (fotos == null && fotoSacada != null) {
            // Se canceló: no dejar una foto vacía en la galería.
            try {
                getContentResolver().delete(fotoSacada, null, null);
            } catch (RuntimeException ignorado) { }
        }
        fotoSacada = null;
        if (fotoPendiente != null) {
            fotoPendiente.onReceiveValue(fotos);
            fotoPendiente = null;
        }
        fotoParams = null;
    }

    @Override
    protected void onActivityResult(int codigo, int resultado, Intent datos) {
        super.onActivityResult(codigo, resultado, datos);
        if (codigo != ELIJO_FOTO) {
            return;
        }
        Uri[] fotos = null;
        if (resultado == RESULT_OK) {
            if (datos != null && datos.getData() != null) {
                fotos = WebChromeClient.FileChooserParams.parseResult(resultado, datos);
                if (fotoSacada != null) {
                    // Eligió de la galería: la foto vacía de la cámara sobra.
                    try {
                        getContentResolver().delete(fotoSacada, null, null);
                    } catch (RuntimeException ignorado) { }
                    fotoSacada = null;
                }
            } else if (fotoSacada != null) {
                fotos = new Uri[]{fotoSacada};
                fotoSacada = null;
            }
        }
        contestarFoto(fotos);
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos,
                                           int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        if (codigo == PIDO_CAMARA_FOTO) {
            // Sin cámara igual se puede elegir de la galería.
            elegirFoto(resultados.length > 0
                    && resultados[0] == PackageManager.PERMISSION_GRANTED);
            return;
        }
        if (codigo == PIDO_UBICACION) {
            // Concedida o no, se sigue con las notificaciones: negar la
            // ubicación no tiene por qué dejar al cadete sin avisos.
            pedirAvisos();
            return;
        }
        if (codigo == PIDO_AVISOS) {
            // Concedido o no, la escucha arranca: sin permiso no va a poder
            // mostrar la notificación, pero el permiso se puede dar después
            // desde los ajustes y entonces ya está escuchando.
            pedirSegundoPlanoYArrancar();
            return;
        }
        if (codigo == PIDO_SEGUNDO_PLANO) {
            // Con o sin "todo el tiempo": sin él sigue registrando mientras
            // la app está abierta.
            UbicacionService.arrancar(this);
            return;
        }
        if (codigo != PIDO_PAGINA) {
            return;
        }
        boolean sinCartel = Permisos.sinCartel(this, codigo, permisos, resultados);
        PermissionRequest pedido = pedidoDeLaPagina;
        String[] recursos = recursosPedidos;
        pedidoDeLaPagina = null;
        recursosPedidos = null;
        if (pedido == null || recursos == null) {
            return;
        }
        List<String> dar = new ArrayList<>();
        for (String recurso : recursos) {
            if (checkSelfPermission(permisoDe(recurso)) == PackageManager.PERMISSION_GRANTED) {
                dar.add(recurso);
            }
        }
        if (!dar.isEmpty()) {
            pedido.grant(dar.toArray(new String[0]));
            return;
        }
        // Se contesta que NO en vez de dejarlo colgado: la página tiene un
        // camino para cuando no hay cámara (tipear la serie) o micrófono (el
        // texto), y sólo lo toma si `getUserMedia` falla.
        pedido.deny();
        if (sinCartel) {
            // Android ya no pregunta (dos "No" seguidos): sin esto la persona
            // toca el botón, no pasa nada, y no sabe que se arregla en ajustes.
            // Es seguro mostrarlo: la cámara y el micrófono los pide la página
            // sólo cuando la persona toca Escanear o Grabar.
            boolean audio = false;
            boolean video = false;
            for (String recurso : recursos) {
                audio |= PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(recurso);
                video |= PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(recurso);
            }
            Permisos.ofrecerAjustes(this, audio && video ? "la cámara y el micrófono"
                    : audio ? "el micrófono" : "la cámara", Permisos.ajustesDeLaApp(this));
        }
    }

    /**
     * La URL que trae el intent de una notificación, si es del sitio.
     *
     * <p>Se valida el prefijo a propósito: un intent puede venir de cualquier
     * app del teléfono, y cargar una URL ajena adentro de un WebView con la
     * sesión del cadete puesta es exactamente cómo se roba una sesión.
     */
    private static String urlDelIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || !url.startsWith(BuildConfig.BASE_URL + "/cadeteria/")) {
            return null;
        }
        return url;
    }

    /** Primer eslabón: la ubicación en primer plano. */
    private void pedirPermisos() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION},
                    PIDO_UBICACION);
            return;
        }
        pedirAvisos();
    }

    private void pedirAvisos() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                               PIDO_AVISOS);
            // El servicio arranca igual cuando conteste (o en el próximo
            // `onResume`): escucha aunque no pueda mostrar la notificación del
            // aviso, y así el cartel de estado no depende de la respuesta.
            return;
        }
        pedirSegundoPlanoYArrancar();
    }

    /**
     * Último eslabón: la ubicación "todo el tiempo", y arrancar los dos
     * servicios. Va DESPUÉS de la de primer plano porque Android rechaza el
     * pedido si vienen juntas (y desde Android 11 ni muestra diálogo: manda a
     * los ajustes).
     */
    private void pedirSegundoPlanoYArrancar() {
        AvisosService.arrancar(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    PIDO_SEGUNDO_PLANO);
        }
        // Arranca ya con el permiso de primer plano; el seguimiento con la
        // pantalla apagada empieza cuando se conceda "todo el tiempo".
        UbicacionService.arrancar(this);
        pedirBateriaSinLimite();
    }

    /**
     * Pide quedar fuera de la optimización de batería. Sin esto Samsung mata
     * el proceso en segundo plano y el servicio de ubicación no vuelve a
     * arrancar hasta que se abre la app: el 2026-09-26 un teléfono dejó de
     * mandar puntos a las 04:50 y no volvió en todo el día. Se pide sólo si
     * ya hay permiso de ubicación (sin eso no hay seguimiento que cuidar), y
     * se vuelve a pedir en cada apertura hasta que se conceda.
     */
    private void pedirBateriaSinLimite() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        PowerManager energia = (PowerManager) getSystemService(POWER_SERVICE);
        if (energia == null || energia.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            // Algún fabricante sin esa pantalla: el seguimiento anda igual
            // mientras la app no quede dormida.
        }
    }

    private void abrirAfuera(Uri destino) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, destino));
        } catch (Exception e) {
            // Sin app que lo atienda no se hace nada: no vale tirar la
            // pantalla abajo por un link.
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        web.saveState(estado);
    }

    @Override
    public void onBackPressed() {
        // Atrás navega dentro del sitio; sólo cierra la app cuando ya no hay
        // a dónde volver.
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
