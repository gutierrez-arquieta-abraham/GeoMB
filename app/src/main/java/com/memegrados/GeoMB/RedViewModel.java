package com.memegrados.GeoMB;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ============================================================
// CLASE    : RedViewModel
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Un VIEWMODEL de la red (Metrobús + Mexibús). Separa los DATOS de la UI:
//   - La carga y el parseo ocurren en un hilo de E/S (GtfsRepository, streaming).
//   - La UI (LinesFragment / SearchFragment / MapFragment) SOLO observa
//     LiveData y dibuja las listas ya construidas, sin tocar archivos ni JSON.
//
// ¿QUÉ ES UN VIEWMODEL? Un componente de Android que GUARDA los datos de la
// pantalla y SOBREVIVE a rotaciones/cambios de configuración → la red se
// parsea UNA sola vez por sesión, no cada vez que gira el teléfono.
//
// LiveData = "dato observable": la UI se suscribe con observe() y se
// redibuja sola cuando el dato cambia.
// ============================================================
/**
 * ViewModel de la RED (Metrobús + Mexibús). Separa la capa de datos de la UI:
 *
 * <ul>
 *   <li>La carga y el parseo ocurren en un hilo de E/S ({@link GtfsRepository}, streaming).</li>
 *   <li>La UI (LinesFragment / SearchFragment / MapFragment) SOLO observa {@link LiveData} y dibuja
 *       las listas de POJOs ya construidas ({@code List<Linea>}), sin tocar archivos ni JSON.</li>
 * </ul>
 *
 * <p>Uso desde un Fragment:
 * <pre>
 *   RedViewModel vm = new ViewModelProvider(this).get(RedViewModel.class);
 *   vm.getMetrobus().observe(getViewLifecycleOwner(), lineas -&gt; dibujar(lineas));
 *   vm.getMexibus().observe(getViewLifecycleOwner(), lineas -&gt; dibujarMexibus(lineas));
 * </pre>
 * Al sobrevivir a rotaciones, la red se parsea una sola vez por sesión.
 */
public class RedViewModel extends AndroidViewModel {

    private final MutableLiveData<List<Linea>> metrobus = new MutableLiveData<>();
    private final MutableLiveData<List<Linea>> mexibus = new MutableLiveData<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "red-vm"); t.setDaemon(true); return t;
    });
    private volatile boolean cargando = false;

    public RedViewModel(@NonNull Application app) { super(app); }

    /** Líneas del Metrobús. La primera llamada dispara la carga en 2º plano. */
    public LiveData<List<Linea>> getMetrobus() { cargar(); return metrobus; }

    /** Líneas del Mexibús. */
    public LiveData<List<Linea>> getMexibus() { cargar(); return mexibus; }

    private void cargar() {
        if (metrobus.getValue() != null || cargando) return;   // ya cargado o en curso
        cargando = true;
        io.execute(() -> {
            List<Linea> mb = GtfsRepository.getLineas(getApplication());   // parseo en streaming (2º plano)
            List<Linea> mx = GtfsRepository.getMexibus(getApplication());
            metrobus.postValue(mb);   // postValue publica en el hilo principal: la UI solo dibuja
            mexibus.postValue(mx);
            cargando = false;
        });
    }

    @Override
    protected void onCleared() {
        io.shutdown();
        super.onCleared();
    }
}
