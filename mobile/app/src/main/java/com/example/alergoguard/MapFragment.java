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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.UrlTileProvider;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;

public class MapFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG                 = "PollenMap";
    private static final float  DEFAULT_ZOOM        = 13f;
    private static final long   REFRESH_INTERVAL_MS = 60_000L;

    private static final String POLLEN_GRASS = "GRASS_UPI";
    private static final String POLLEN_TREE  = "TREE_UPI";
    private static final String POLLEN_WEED  = "WEED_UPI";

    private GoogleMap googleMap;
    private FusedLocationProviderClient fusedLocationClient;
    private final CancellationTokenSource cancellationSource = new CancellationTokenSource();

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

    // ── Location permission launcher ──────────────────────────────────────────
    private final ActivityResultLauncher<String> locationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            Log.i(TAG, "Location permission granted — loading map");
                            loadLocationAndMap();
                        } else {
                            Log.w(TAG, "Location permission denied");
                            Toast.makeText(requireContext(),
                                    "Location permission is needed to show pollen near you.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

        // Bottom sheet — hideable=false is already set in XML.
        // No setState needed; default is STATE_COLLAPSED which shows the peek.
        NestedScrollView bottomSheet = view.findViewById(R.id.bottom_sheet);
        BottomSheetBehavior.from(bottomSheet).setHideable(false);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map_view);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        FloatingActionButton fabRecenter = view.findViewById(R.id.fab_recenter);
        fabRecenter.setOnClickListener(v -> recenterOnUser());
    }

    @Override
    public void onResume() {
        super.onResume();
        startPollenRefreshLoop();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPollenRefreshLoop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancellationSource.cancel();
    }

    // ── Map ready ─────────────────────────────────────────────────────────────

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        try {
            googleMap.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_light));
        } catch (Exception ignored) { }

        googleMap.getUiSettings().setZoomControlsEnabled(false);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);

        requestLocationAndLoad();
    }

    // ── Permission + location ─────────────────────────────────────────────────

    private void requestLocationAndLoad() {
        boolean granted = ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!granted) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        loadLocationAndMap();
    }

    @SuppressWarnings("MissingPermission")
    private void loadLocationAndMap() {
        if (googleMap != null) googleMap.setMyLocationEnabled(true);

        // getCurrentLocation() forces a fresh fix — getLastLocation() returns null
        // on fresh installs / after reboot when there is no cached position yet.
        CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build();

        fusedLocationClient
                .getCurrentLocation(request, cancellationSource.getToken())
                .addOnSuccessListener(location -> {
                    if (!isAdded()) return;
                    if (location != null) {
                        onLocationObtained(location);
                    } else {
                        Log.w(TAG, "getCurrentLocation() returned null — is GPS on?");
                        Toast.makeText(requireContext(),
                                "Could not get your location. Is GPS enabled?",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    Log.e(TAG, "Location fetch failed: " + e.getMessage());
                    Toast.makeText(requireContext(),
                            "Location error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void onLocationObtained(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM));
        resolveLocationName(location.getLatitude(), location.getLongitude());
        addPollenOverlays();
        fetchAllergenData(location.getLatitude(), location.getLongitude());
    }

    // ── Pollen overlays ───────────────────────────────────────────────────────

    private void addPollenOverlays() {
        if (googleMap == null || googleApiKey == null) return;
        removePollenOverlays();

        if (cbWeed.isChecked()) {
            weedOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                    .tileProvider(buildPollenTileProvider(POLLEN_WEED))
                    .transparency(0.15f).zIndex(10f));
        }
        if (cbTree.isChecked()) {
            treeOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                    .tileProvider(buildPollenTileProvider(POLLEN_TREE))
                    .transparency(0.20f).zIndex(5f));
        }
        if (cbGrass.isChecked()) {
            grassOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                    .tileProvider(buildPollenTileProvider(POLLEN_GRASS))
                    .transparency(0.20f).zIndex(2f));
        }
    }

    private UrlTileProvider buildPollenTileProvider(String pollenType) {
        return new UrlTileProvider(256, 256) {
            @Override
            public URL getTileUrl(int x, int y, int zoom) {
                if (googleApiKey == null || googleApiKey.isEmpty()) return null;
                String url = "https://pollen.googleapis.com/v1/mapTypes/" + pollenType
                        + "/heatmapTiles/" + zoom + "/" + x + "/" + y + "?key=" + googleApiKey;
                try {
                    return new URL(url);
                } catch (MalformedURLException e) {
                    Log.e(TAG, "Malformed tile URL: " + url);
                    return null;
                }
            }
        };
    }

    private void removePollenOverlays() {
        if (grassOverlay != null) { grassOverlay.remove(); grassOverlay = null; }
        if (treeOverlay  != null) { treeOverlay.remove();  treeOverlay  = null; }
        if (weedOverlay  != null) { weedOverlay.remove();  weedOverlay  = null; }
    }

    // ── Recenter ──────────────────────────────────────────────────────────────

    private void recenterOnUser() {
        if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && googleMap != null) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(location.getLatitude(), location.getLongitude()), DEFAULT_ZOOM));
            }
        });
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    private void resolveLocationName(double lat, double lng) {
        new Thread(() -> {
            String city = "Unknown location", country = "";
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
                    List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address a = addresses.get(0);
                        city    = a.getLocality() != null ? a.getLocality() : a.getAdminArea();
                        country = a.getCountryName();
                    }
                }
            } catch (Exception ignored) { }
            final String fc = city, fn = country;
            if (isAdded()) requireActivity().runOnUiThread(() -> {
                tvLocationName.setText(fc);
                tvLocationSub.setText(fn.isEmpty() ? "Tracking your location" : fn);
            });
        }).start();
    }

    // ── Allergen UI ───────────────────────────────────────────────────────────

    private void fetchAllergenData(double lat, double lng) {
        mockAllergenResponse();
    }

    public void updateAllergenUI(String g, String t, String m, String w,
                                 String risk, String title, String rec) {
        if (tvGrassLevel == null) return;
        tvGrassLevel.setText(capitalize(g)); tvTreeLevel.setText(capitalize(t));
        tvMoldLevel.setText(capitalize(m));  tvWindLevel.setText(capitalize(w));
        tvGrassLevel.setTextColor(levelColor(g));
        tvTreeLevel.setTextColor(levelColor(t));
        updateRiskBadge(risk);
        if (title != null) {
            cardAlert.setVisibility(View.VISIBLE);
            tvAlertTitle.setText(title);
            tvAlertSub.setText(rec);
        }
    }

    private void updateRiskBadge(String risk) {
        tvRiskBadge.setText(risk.toUpperCase() + " RISK");
        tvRiskBadge.setTextColor(levelColor(risk));
    }

    private int levelColor(String l) {
        switch (l.toLowerCase()) {
            case "high":   return requireContext().getColor(R.color.risk_high);
            case "medium": return requireContext().getColor(R.color.risk_medium);
            default:       return requireContext().getColor(R.color.risk_low);
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "—";
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private void mockAllergenResponse() {
        updateAllergenUI("low", "high", "medium", "low", "high",
                "High Tree Pollen in Belgrade",
                "Current spring bloom is intense. Avoid parks today.");
    }

    // ── Auto-refresh ──────────────────────────────────────────────────────────

    private void startPollenRefreshLoop() {
        refreshRunnable = () -> {
            if (isAdded() && googleMap != null) addPollenOverlays();
            refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
        };
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void stopPollenRefreshLoop() {
        if (refreshRunnable != null) refreshHandler.removeCallbacks(refreshRunnable);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        cbGrass        = view.findViewById(R.id.cb_grass);
        cbTree         = view.findViewById(R.id.cb_tree);
        cbWeed         = view.findViewById(R.id.cb_weed);
    }

    private void setupToggleListeners() {
        cbGrass.setOnCheckedChangeListener((v, checked) -> addPollenOverlays());
        cbTree.setOnCheckedChangeListener( (v, checked) -> addPollenOverlays());
        cbWeed.setOnCheckedChangeListener( (v, checked) -> addPollenOverlays());
    }

    private void readApiKey() {
        try {
            ApplicationInfo ai = requireContext().getPackageManager()
                    .getApplicationInfo(requireContext().getPackageName(),
                            PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                googleApiKey = ai.metaData.getString("com.google.android.geo.API_KEY");
                Log.d(TAG, "API Key: " + (googleApiKey != null ? "loaded" : "MISSING"));
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
    }
}