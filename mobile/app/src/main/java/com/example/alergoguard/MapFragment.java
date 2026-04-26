package com.example.alergoguard;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.UrlTileProvider;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;

public class MapFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "PollenMap";
    private static final int    LOCATION_PERMISSION_REQUEST = 1001;
    private static final float  DEFAULT_ZOOM                = 13f;
    private static final long   REFRESH_INTERVAL_MS         = 60_000L;

    private static final String POLLEN_GRASS = "GRASS_UPI";
    private static final String POLLEN_TREE  = "TREE_UPI";
    private static final String POLLEN_WEED  = "WEED_UPI";

    private GoogleMap googleMap;
    private FusedLocationProviderClient fusedLocationClient;

    private TileOverlay grassOverlay;
    private TileOverlay treeOverlay;
    private TileOverlay weedOverlay;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private String googleApiKey = null;

    // Views
    private TextView tvLocationName;
    private TextView tvLocationSub;
    private TextView tvRiskBadge;
    private TextView tvGrassLevel;
    private TextView tvTreeLevel;
    private TextView tvMoldLevel;
    private TextView tvWindLevel;
    private CardView cardAlert;
    private TextView tvAlertTitle;
    private TextView tvAlertSub;

    // Toggle Views
    private CheckBox cbGrass;
    private CheckBox cbTree;
    private CheckBox cbWeed;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);
        readApiKey();
        setupToggleListeners();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map_view);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        FloatingActionButton fabRecenter = view.findViewById(R.id.fab_recenter);
        fabRecenter.setOnClickListener(v -> recenterOnUser());
    }

    private void bindViews(View view) {
        tvLocationName = view.findViewById(R.id.tv_location_name);
        tvLocationSub  = view.findViewById(R.id.tv_location_sub);
        tvRiskBadge    = view.findViewById(R.id.tv_risk_badge);
        tvGrassLevel   = view.findViewById(R.id.tv_grass_level);
        tvTreeLevel    = view.findViewById(R.id.tv_tree_level);
        tvMoldLevel    = view.findViewById(R.id.tv_mold_level);
        tvWindLevel    = view.findViewById(R.id.tv_wind_level);
        cardAlert      = view.findViewById(R.id.card_alert);
        tvAlertTitle   = view.findViewById(R.id.tv_alert_title);
        tvAlertSub     = view.findViewById(R.id.tv_alert_sub);

        cbGrass = view.findViewById(R.id.cb_grass);
        cbTree  = view.findViewById(R.id.cb_tree);
        cbWeed  = view.findViewById(R.id.cb_weed);
    }

    private void setupToggleListeners() {
        cbGrass.setOnCheckedChangeListener((v, isChecked) -> addPollenOverlays());
        cbTree.setOnCheckedChangeListener((v, isChecked) -> addPollenOverlays());
        cbWeed.setOnCheckedChangeListener((v, isChecked) -> addPollenOverlays());
    }

    private void readApiKey() {
        try {
            ApplicationInfo ai = requireContext().getPackageManager()
                    .getApplicationInfo(requireContext().getPackageName(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                googleApiKey = ai.metaData.getString("com.google.android.geo.API_KEY");
                Log.d(TAG, "API Key loaded: " + (googleApiKey != null ? "SUCCESS" : "FAILED"));
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        try {
            googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_light));
        } catch (Exception ignored) { }

        googleMap.getUiSettings().setZoomControlsEnabled(false);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        requestLocationAndLoad();
    }

    private void requestLocationAndLoad() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            return;
        }
        if (googleMap != null) googleMap.setMyLocationEnabled(true);
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) onLocationObtained(location);
        });
    }

    private void onLocationObtained(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM));
        resolveLocationName(location.getLatitude(), location.getLongitude());
        addPollenOverlays();
        fetchAllergenData(location.getLatitude(), location.getLongitude());
    }

    /**
     * Logic for stacking overlays.
     * NOTE: If Weed is checked but the map is blank, try checking "Tree".
     * Tree pollen is currently active in Belgrade and more likely to show color.
     */
    private void addPollenOverlays() {
        if (googleMap == null || googleApiKey == null) return;
        removePollenOverlays();

        // 1. Weed Layer (Ragweed)
        if (cbWeed.isChecked()) {
            weedOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                    .tileProvider(buildPollenTileProvider(POLLEN_WEED))
                    .transparency(0.15f)
                    .zIndex(10f));
        }

        // 2. Tree Layer (Spring fallback - very likely to show color right now)
        if (cbTree.isChecked()) {
            treeOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                    .tileProvider(buildPollenTileProvider(POLLEN_TREE))
                    .transparency(0.20f)
                    .zIndex(5f));
        }

        // 3. Grass Layer
        if (cbGrass.isChecked()) {
            grassOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                    .tileProvider(buildPollenTileProvider(POLLEN_GRASS))
                    .transparency(0.20f)
                    .zIndex(2f));
        }

        /* Old static code commented out
        // weedOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
        //        .tileProvider(buildPollenTileProvider(POLLEN_WEED))
        //        .transparency(0.20f)
        //        .zIndex(10f));
        */
    }

    private UrlTileProvider buildPollenTileProvider(String pollenType) {
        return new UrlTileProvider(256, 256) {
            @Override
            public URL getTileUrl(int x, int y, int zoom) {
                if (googleApiKey == null || googleApiKey.isEmpty()) return null;

                String urlString = "https://pollen.googleapis.com/v1/mapTypes/" + pollenType
                        + "/heatmapTiles/" + zoom + "/" + x + "/" + y + "?key=" + googleApiKey;

                try {
                    return new URL(urlString);
                } catch (MalformedURLException e) {
                    Log.e(TAG, "Malformed URL for tile: " + urlString);
                    return null;
                }
            }
        };
    }

    private void removePollenOverlays() {
        if (grassOverlay != null) { grassOverlay.remove(); grassOverlay = null; }
        if (treeOverlay != null) { treeOverlay.remove(); treeOverlay = null; }
        if (weedOverlay != null) { weedOverlay.remove(); weedOverlay = null; }
    }

    private void resolveLocationName(double lat, double lng) {
        new Thread(() -> {
            String city = "Unknown location", country = "";
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
                    List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address a = addresses.get(0);
                        city = a.getLocality() != null ? a.getLocality() : a.getAdminArea();
                        country = a.getCountryName();
                    }
                }
            } catch (Exception ignored) { }
            final String fc = city; final String fn = country;
            if (isAdded()) requireActivity().runOnUiThread(() -> {
                tvLocationName.setText(fc);
                tvLocationSub.setText(fn.isEmpty() ? "Tracking your location" : fn);
            });
        }).start();
    }

    private void recenterOnUser() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null && googleMap != null) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(), location.getLongitude()), DEFAULT_ZOOM));
                }
            });
        }
    }

    private void fetchAllergenData(double lat, double lng) { mockAllergenResponse(); }

    public void updateAllergenUI(String g, String t, String m, String w, String risk, String title, String rec) {
        if (tvGrassLevel == null) return;
        tvGrassLevel.setText(capitalize(g)); tvTreeLevel.setText(capitalize(t));
        tvMoldLevel.setText(capitalize(m)); tvWindLevel.setText(capitalize(w));
        tvGrassLevel.setTextColor(levelColor(g)); tvTreeLevel.setTextColor(levelColor(t));
        updateRiskBadge(risk);
        if (title != null) {
            cardAlert.setVisibility(View.VISIBLE);
            tvAlertTitle.setText(title); tvAlertSub.setText(rec);
        }
    }

    private void updateRiskBadge(String risk) {
        tvRiskBadge.setText(risk.toUpperCase() + " RISK");
        int color = levelColor(risk);
        tvRiskBadge.setTextColor(color);
    }

    private int levelColor(String l) {
        switch (l.toLowerCase()) {
            case "high": return requireContext().getColor(R.color.risk_high);
            case "medium": return requireContext().getColor(R.color.risk_medium);
            default: return requireContext().getColor(R.color.risk_low);
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "—";
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private void mockAllergenResponse() {
        updateAllergenUI("low", "high", "medium", "low", "high", "High Tree Pollen in Belgrade", "Current spring bloom is intense. Avoid parks today.");
    }

    @Override
    public void onResume() { super.onResume(); startPollenRefreshLoop(); }
    @Override
    public void onPause() { super.onPause(); stopPollenRefreshLoop(); }

    private void startPollenRefreshLoop() {
        refreshRunnable = () -> {
            if (isAdded() && googleMap != null) { addPollenOverlays(); }
            refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
        };
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void stopPollenRefreshLoop() { if (refreshRunnable != null) refreshHandler.removeCallbacks(refreshRunnable); }
}