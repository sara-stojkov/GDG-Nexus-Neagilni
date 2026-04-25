package com.example.alergoguard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
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
import com.google.android.gms.maps.model.TileProvider;
import com.google.android.gms.maps.model.UrlTileProvider;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.net.MalformedURLException;
import java.net.URL;

public class MapFragment extends Fragment implements OnMapReadyCallback {

    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final float DEFAULT_ZOOM = 13f;

    // Backend base URL — replace with your FastAPI server
    private static final String API_BASE = "https://your-api.example.com";

    private GoogleMap googleMap;
    private FusedLocationProviderClient fusedLocationClient;
    private TileOverlay heatmapOverlay;

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
    }

    // ── Google Maps ──────────────────────────────────────────────────────────

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;

        // Light map style — cleaner background for the heatmap overlay
        try {
            googleMap.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_light)
            );
        } catch (Exception e) {
            // Falls back to default Google Maps style
        }

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
                LOCATION_PERMISSION_REQUEST
            );
            return;
        }

        googleMap.setMyLocationEnabled(true);

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                onLocationObtained(location);
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
        }
    }

    private void onLocationObtained(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

        // Move camera
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM));

        // Show reverse-geocoded city name (simplified — swap for Geocoder if desired)
        tvLocationName.setText("Pančevo, Vojvodina"); // Replace with geocoder result
        tvLocationSub.setText("Tracking your location");

        // Add heatmap tile overlay from your backend
        addHeatmapOverlay();

        // Fetch allergen data for this coordinate
        fetchAllergenData(location.getLatitude(), location.getLongitude());
    }

    private void recenterOnUser() {
        if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && googleMap != null) {
                LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM));
            }
        });
    }

    // ── Heatmap tile overlay ──────────────────────────────────────────────────
    //
    // Your FastAPI backend should serve XYZ map tiles at:
    //   GET /heatmap/tiles/{z}/{x}/{y}.png
    // These are standard slippy map tiles (256x256 PNG) with the pollen
    // intensity rendered as a color-coded heatmap by the backend.

    private void addHeatmapOverlay() {
        if (heatmapOverlay != null) {
            heatmapOverlay.remove();
        }

        TileProvider tileProvider = new UrlTileProvider(256, 256) {
            @Override
            public URL getTileUrl(int x, int y, int zoom) {
                String url = API_BASE + "/heatmap/tiles/" + zoom + "/" + x + "/" + y + ".png";
                try {
                    return new URL(url);
                } catch (MalformedURLException e) {
                    return null;
                }
            }
        };

        heatmapOverlay = googleMap.addTileOverlay(
            new TileOverlayOptions()
                .tileProvider(tileProvider)
                .transparency(0.2f)   // 0 = fully opaque, 1 = invisible
                .zIndex(1f)
        );
    }

    // ── Allergen data ─────────────────────────────────────────────────────────
    //
    // Calls your FastAPI endpoint:
    //   GET /allergens?lat={lat}&lng={lng}
    // Expected JSON response:
    // {
    //   "grass": "high" | "medium" | "low",
    //   "tree":  "high" | "medium" | "low",
    //   "mold":  "high" | "medium" | "low",
    //   "wind":  "high" | "medium" | "low",
    //   "overall_risk": "high" | "medium" | "low",
    //   "alert": { "title": "...", "recommendation": "..." }  // optional
    // }

    private void fetchAllergenData(double lat, double lng) {
        // TODO: wire to your Retrofit/OkHttp client
        // For now, populate with mock data so the UI is testable immediately.
        // Replace this block with your actual API call.
        mockAllergenResponse();
    }

    // ── UI update helpers ─────────────────────────────────────────────────────

    /**
     * Call this from your actual API response handler once Retrofit is wired up.
     * levelGrass / levelTree / levelMold / levelWind: "high", "medium", or "low"
     */
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
            case "low":
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

    // ── Mock data (remove once API is wired) ──────────────────────────────────

    private void mockAllergenResponse() {
        updateAllergenUI(
            "high", "high", "medium", "medium",
            "high",
            "High grass pollen near you",
            "Consider taking Cetirizine before going outside"
        );
    }
}
