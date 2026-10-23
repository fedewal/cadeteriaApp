package today.scrappy.cadeteria;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * La sección de Salud: qué está bien y qué no en el teléfono, con un botón para
 * arreglar cada cosa. Los permisos se piden con el cartel "Permitir" de Android
 * sin salir de la app ({@link Permisos}); lo demás (GPS, ahorro de batería,
 * instalar actualizaciones) Android sólo lo deja cambiar en sus ajustes, y el
 * botón lleva a la pantalla exacta.
 *
 * <p>Nativa y no una página de Django a propósito: lo que revisa (permisos,
 * GPS, ahorro de batería) sólo lo sabe el teléfono, y tiene que abrir aunque no
 * haya red — que es a veces justamente el problema. Se arma en código porque la
 * app no tiene recursos de layout ni dependencias (ver build.gradle).
 *
 * <p>Se recalcula al volver ({@code onResume}): la persona toca "Arreglar", va a
 * los ajustes, vuelve, y ve el tilde.
 */
public class SaludActivity extends Activity {

    private static final int AZUL = 0xFF242F62;
    private static final int VERDE = 0xFF2E9E5B;
    private static final int ROJO = 0xFFD63C3C;
    private static final int GRIS = 0xFF6B7280;
    private static final int FONDO = 0xFFF3F5F9;

    private LinearLayout lista;

    /** Código de {@code requestPermissions} = 100 + posición del ítem. */
    private static final int PIDO_DESDE = 100;
    /** Los ítems tal como se pintaron, para saber qué ítem contestó Android. */
    private java.util.List<Salud.Item> pintados = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);
        getWindow().setStatusBarColor(AZUL);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(FONDO);

        TextView titulo = texto("Salud del teléfono", 21, Color.WHITE, true);
        titulo.setBackgroundColor(AZUL);
        titulo.setPadding(dp(18), dp(18), dp(18), dp(6));
        raiz.addView(titulo);
        TextView sub = texto("Lo que tiene que estar bien para que la app funcione.",
                14, 0xDDFFFFFF, false);
        sub.setBackgroundColor(AZUL);
        sub.setPadding(dp(18), 0, dp(18), dp(16));
        raiz.addView(sub);

        ScrollView scroll = new ScrollView(this);
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        lista.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(lista);
        raiz.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pintar();
    }

    private void pintar() {
        lista.removeAllViews();
        pintados = Salud.revisar(this);
        for (int i = 0; i < pintados.size(); i++) {
            final Salud.Item item = pintados.get(i);
            final int codigo = PIDO_DESDE + i;
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.VERTICAL);
            fila.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable fondo = new GradientDrawable();
            fondo.setColor(Color.WHITE);
            fondo.setCornerRadius(dp(14));
            fila.setBackground(fondo);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);

            fila.addView(texto((item.ok ? "✓  " : "✗  ") + item.titulo, 16,
                    item.ok ? VERDE : (item.critico ? ROJO : 0xFFB45309), true));
            TextView detalle = texto(item.detalle, 14, GRIS, false);
            detalle.setPadding(0, dp(3), 0, 0);
            fila.addView(detalle);

            if (!item.ok && (item.arreglar != null || item.permisos != null)) {
                Button arreglar = new Button(this);
                arreglar.setText("Arreglar");
                arreglar.setAllCaps(false);
                arreglar.setTextColor(Color.WHITE);
                GradientDrawable fb = new GradientDrawable();
                fb.setColor(AZUL);
                fb.setCornerRadius(dp(10));
                arreglar.setBackground(fb);
                arreglar.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (item.permisos != null) {
                            Permisos.pedir(SaludActivity.this, item.permisos, codigo);
                        } else {
                            // Un fabricante sin esa pantalla cae a los ajustes
                            // de la app, donde está casi todo.
                            Permisos.abrir(SaludActivity.this, item.arreglar);
                        }
                    }
                });
                LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
                lb.topMargin = dp(10);
                fila.addView(arreglar, lb);
            }
            lista.addView(fila, lp);
        }
        TextView version = texto("Versión " + BuildConfig.VERSION_NAME, 12, GRIS, false);
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        version.setPadding(0, dp(8), 0, 0);
        lista.addView(version);
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos,
                                           int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        int i = codigo - PIDO_DESDE;
        Salud.Item item = i >= 0 && i < pintados.size() ? pintados.get(i) : null;
        boolean sinCartel = Permisos.sinCartel(this, codigo, permisos, resultados);
        if (Permisos.algunoConcedido(resultados)) {
            // Con la ubicación recién dada el seguimiento arranca ya, sin
            // esperar a que se vuelva a la pantalla principal.
            UbicacionService.arrancar(this);
        } else if (item != null && sinCartel) {
            Permisos.ofrecerAjustes(this, item.que, item.arreglar);
        }
        pintar();
    }

    private TextView texto(String s, float sp, int color, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (negrita) {
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return t;
    }

    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }
}
