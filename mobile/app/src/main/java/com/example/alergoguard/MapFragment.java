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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    private static final int    LOCATION_PERMISSION_REQUEST = 1001;
    private static final float  DEFAULT_ZOOM                = 13f;
    private static final long   REFRESH_INTERVAL_MS         = 60_000L; // 1 minute

    // Google Pollen API tile types — toggle via chip/button if you add one later
    private static final String POLLEN_GRASS = "GRASS_UPI";
    private static final String POLLEN_TREE  = "TREE_UPI";
    private static final String POLLEN_WEED  = "WEED_UPI";

    private static final String API_BASE = "https://your-api.example.com";

    private GoogleMap googleMap;
    private FusedLocationProviderClient fusedLocationClient;

    // We keep one overlay per pollen type so all three can stack
    private TileOverlay grassOverlay;
    private TileOverlay treeOverlay;
    private TileOverlay weedOverlay;

    // Periodic refresh
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private String googleApiKey = null; // read once from manifest metadata

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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map_view);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Toast.makeText(requireContext(), "Map Fragment component not found", Toast.LENGTH_LONG).show();
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
        stopPollenRefreshLoop(); // don't refresh while off-screen
    }

    // ── API key ───────────────────────────────────────────────────────────────

    /**
     * Reads the Google Maps / Pollen API key from AndroidManifest metadata.
     * This is the same key already declared as ${googleMapsKey}.
     */
    private void readApiKey() {
        try {
            ApplicationInfo ai = requireContext().getPackageManager()
                    .getApplicationInfo(
                            requireContext().getPackageName(),
                            PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                googleApiKey = ai.metaData.getString("com.google.android.geo.API_KEY");
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Should never happen — package is always found
        }
    }

    // ── Views ─────────────────────────────────────────────────────────────────

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
    }

    // ── Google Maps ──────────────────────────────────────────────────────────

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;

        try {
            googleMap.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_light));
        } catch (Exception ignored) { }

        googleMap.getUiSettings().setZoomControlsEnabled(false);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        googleMap.getUiSettings().setMapToolbarEnabled(false);

        requestLocationAndLoad();
    }

    // ── Location ─────────────────────────────────────────────────────────────

    private void requestLocationAndLoad() {
        if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
            return;
        }

        if (googleMap != null) googleMap.setMyLocationEnabled(true);

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                onLocationObtained(location);
            } else {
                Toast.makeText(requireContext(),
                        "Unable to get current location", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocationAndLoad();
        } else {
            Toast.makeText(requireContext(),
                    "Permission denied. Map center unavailable.", Toast.LENGTH_SHORT).show();
        }
    }

    private void onLocationObtained(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM));

        tvLocationName.setText("Locating…");
        tvLocationSub.setText("Tracking your location");

        resolveLocationName(location.getLatitude(), location.getLongitude());
        addPollenOverlays(); // first draw immediately
        fetchAllergenData(location.getLatitude(), location.getLongitude());
    }

    private void resolveLocationName(double lat, double lng) {
        new Thread(() -> {
            String city = null, country = null;
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
                    List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address a = addresses.get(0);
                        city    = a.getLocality();
                        if (city == null) city = a.getSubAdminArea();
                        if (city == null) city = a.getAdminArea();
                        country = a.getCountryName();
                    }
                }
            } catch (Exception ignored) { }

            final String fc = (city    != null) ? city    : "Unknown location";
            final String fn = (country != null) ? country : "";

            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    tvLocationName.setText(fc);
                    tvLocationSub.setText(fn.isEmpty() ? "Tracking your location" : fn);
                });
            }
        }).start();
    }

    private void recenterOnUser() {
        if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && googleMap != null) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(location.getLatitude(), location.getLongitude()), DEFAULT_ZOOM));
            }
        });
    }

    // ── Pollen heatmap overlays ───────────────────────────────────────────────

    /**
     * Builds one UrlTileProvider that points at Google's Pollen API tile endpoint.
     * The API key is the same one used for Google Maps — no extra setup needed
     * as long as the "Pollen API" is enabled in your Google Cloud project.
     *
     * Tile URL format:
     *   https://pollen.googleapis.com/v1/mapTypes/{TYPE}/heatmapTiles/{zoom}/{x}/{y}?key={KEY}
     */
    private UrlTileProvider buildPollenTileProvider(String pollenType) {
        return new UrlTileProvider(256, 256) {
            @Override
            public URL getTileUrl(int x, int y, int zoom) {
                if (googleApiKey == null || googleApiKey.isEmpty()) return null;
                String url = "https://pollen.googleapis.com/v1/mapTypes/"
                        + pollenType
                        + "/heatmapTiles/" + zoom + "/" + x + "/" + y
                        + "?key=" + googleApiKey;
                try {
                    return new URL(url);
                } catch (MalformedURLException e) {
                    return null;
                }
            }
        };
    }

    /**
     * Removes existing overlays and re-adds fresh ones.
     * Called immediately on location obtained, then every REFRESH_INTERVAL_MS.
     * Clearing and re-adding forces the tile cache to reload new data.
     */
    private void addPollenOverlays() {
        if (googleMap == null || googleApiKey == null) return;

        removePollenOverlays();

        // Grass — most visible, slightly more opaque
        grassOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                .tileProvider(buildPollenTileProvider(POLLEN_GRASS))
                .transparency(0.25f)
                .zIndex(2f));

        // Tree — middle layer
        treeOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                .tileProvider(buildPollenTileProvider(POLLEN_TREE))
                .transparency(0.35f)
                .zIndex(1f));

        // Weed — bottom layer, most transparent
        weedOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                .tileProvider(buildPollenTileProvider(POLLEN_WEED))
                .transparency(0.45f)
                .zIndex(0f));
    }

    private void removePollenOverlays() {
        if (grassOverlay != null) { grassOverlay.remove(); grassOverlay = null; }
        if (treeOverlay  != null) { treeOverlay.remove();  treeOverlay  = null; }
        if (weedOverlay  != null) { weedOverlay.remove();  weedOverlay  = null; }
    }

    // ── Periodic refresh ──────────────────────────────────────────────────────

    private void startPollenRefreshLoop() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAdded() && googleMap != null) {
                    addPollenOverlays();                          // refresh tiles
                    refreshUserLocation();                        // refresh location pin
                }
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        };
        // First tick after 1 minute (initial load happens in onLocationObtained)
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void stopPollenRefreshLoop() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }
    }

    private void refreshUserLocation() {
        if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && isAdded()) {
                fetchAllergenData(location.getLatitude(), location.getLongitude());
            }
        });
    }

    // ── Allergen data (your FastAPI backend) ──────────────────────────────────

    private void fetchAllergenData(double lat, double lng) {
        // TODO: replace mock with real API call to API_BASE
        mockAllergenResponse();
    }

    public void updateAllergenUI(String levelGrass, String levelTree,
                                 String levelMold, String levelWind,
                                 String overallRisk,
                                 @Nullable String alertTitle,
                                 @Nullable String alertRecommendation) {
        tvGrassLevel.setText(capitalize(levelGrass));
        tvTreeLevel.setText(capitalize(levelTree));
        tvMoldLevel.setText(capitalize(levelMold));
        tvWindLevel.setText(capitalize(levelWind));

        tvGrassLevel.setTextColor(levelColor(levelGrass));
        tvTreeLevel.setTextColor(levelColor(levelTree));
        tvMoldLevel.setTextColor(levelColor(levelMold));
        tvWindLevel.setTextColor(levelColor(levelWind));

        updateRiskBadge(overallRisk);

        if (alertTitle != null && !alertTitle.isEmpty()) {
            cardAlert.setVisibility(View.VISIBLE);
            tvAlertTitle.setText(alertTitle);
            tvAlertSub.setText(alertRecommendation != null ? alertRecommendation : "");
        } else {
            cardAlert.setVisibility(View.GONE);
        }
    }

    private void updateRiskBadge(String risk) {
        tvRiskBadge.setText(risk.toUpperCase() + " RISK");
        switch (risk.toLowerCase()) {
            case "high":
                tvRiskBadge.setBackgroundResource(R.drawable.bg_badge_high);
                tvRiskBadge.setTextColor(requireContext().getColor(R.color.risk_high));
                break;
            case "medium":
                tvRiskBadge.setBackgroundResource(R.drawable.bg_badge_medium);
                tvRiskBadge.setTextColor(requireContext().getColor(R.color.risk_medium));
                break;
            default:
                tvRiskBadge.setBackgroundResource(R.drawable.bg_badge_low);
                tvRiskBadge.setTextColor(requireContext().getColor(R.color.risk_low));
                break;
        }
    }

    private int levelColor(String level) {
        switch (level.toLowerCase()) {
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
        updateAllergenUI(
                "high", "high", "medium", "medium",
                "high",
                "High grass pollen near you",
                "Consider taking Cetirizine before going outside"
        );
    }
}