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
        } else {
            // Check if fragment ID is correct in your XML
            Toast.makeText(requireContext(), "Map Fragment component not found", Toast.LENGTH_LONG).show();
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
        if (map == null) {
            Toast.makeText(requireContext(), "Google Maps could not be initialized", Toast.LENGTH_LONG).show();
            return;
        }

        googleMap = map;

        try {
            googleMap.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_light)
            );
        } catch (Exception e) {
            // Falls back to default style
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

        if (googleMap != null) {
            googleMap.setMyLocationEnabled(true);
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                onLocationObtained(location);
            } else {
                Toast.makeText(requireContext(), "Unable to get current location", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(requireContext(), "Permission denied. Map center unavailable.", Toast.LENGTH_SHORT).show();
        }
    }

    private void onLocationObtained(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM));

        tvLocationName.setText("Novi Sad, Serbia");
        tvLocationSub.setText("Tracking your location");

        addHeatmapOverlay();
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
                        .transparency(0.2f)
                        .zIndex(1f)
        );
    }

    private void fetchAllergenData(double lat, double lng) {
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

    private void mockAllergenResponse() {
        updateAllergenUI(
                "high", "high", "medium", "medium",
                "high",
                "High grass pollen near you",
                "Consider taking Cetirizine before going outside"
        );
    }
}