package com.example.alergoguard;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Profile screen.
 * Shows personal info, allergy profile, medications, and learned thresholds.
 * All data is mocked — wire up Firebase in the next sprint.
 */
public class ProfileFragment extends Fragment {

    // ── Mock data ─────────────────────────────────────────────────────────────

    private String mockName        = "Marko Jovanović";
    private String mockAge         = "28";
    private String mockCity        = "Novi Sad, Serbia";
    private String mockEmail       = "marko.j@email.com";

    private final List<String> mockAllergies  = new ArrayList<>(Arrays.asList(
            "Grass pollen", "Birch tree pollen", "Dust mites"
    ));

    private final List<String> mockMedicines  = new ArrayList<>(Arrays.asList(
            "Cetirizine 10 mg (morning)",
            "Fluticasone nasal spray (as needed)"
    ));

    // Learned thresholds — will come from ML model later
    private static final String THRESHOLD_GRASS  = "High  (>80 grains/m³)";
    private static final String THRESHOLD_TREE   = "Medium  (>50 grains/m³)";
    private static final String THRESHOLD_MOLD   = "Low  (>20 spores/m³)";
    private static final String SENSITIVITY_LABEL = "High sensitivity";
    private static final String SNEEZES_TODAY    = "7 sneezes logged today";
    private static final String LAST_REACTION    = "Last reaction: 2 days ago";

    // ── Views ─────────────────────────────────────────────────────────────────

    private TextView tvName, tvAge, tvCity, tvEmail;
    private TextView tvSensitivityLabel, tvSneezesToday, tvLastReaction;
    private TextView tvThresholdGrass, tvThresholdTree, tvThresholdMold;
    private LinearLayout containerAllergies, containerMedicines;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);
        populateMockData();
        setupEditButtons(view);
        buildAllergyChips();
        buildMedicineRows();
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private void bindViews(View view) {
        tvName            = view.findViewById(R.id.tv_profile_name);
        tvAge             = view.findViewById(R.id.tv_profile_age);
        tvCity            = view.findViewById(R.id.tv_profile_city);
        tvEmail           = view.findViewById(R.id.tv_profile_email);

        tvSensitivityLabel = view.findViewById(R.id.tv_sensitivity_label);
        tvSneezesToday     = view.findViewById(R.id.tv_sneezes_today);
        tvLastReaction     = view.findViewById(R.id.tv_last_reaction);

        tvThresholdGrass  = view.findViewById(R.id.tv_threshold_grass);
        tvThresholdTree   = view.findViewById(R.id.tv_threshold_tree);
        tvThresholdMold   = view.findViewById(R.id.tv_threshold_mold);

        containerAllergies = view.findViewById(R.id.container_allergies);
        containerMedicines = view.findViewById(R.id.container_medicines);
    }

    private void populateMockData() {
        tvName.setText(mockName);
        tvAge.setText(mockAge + " yrs");
        tvCity.setText(mockCity);
        tvEmail.setText(mockEmail);

        tvSensitivityLabel.setText(SENSITIVITY_LABEL);
        tvSneezesToday.setText(SNEEZES_TODAY);
        tvLastReaction.setText(LAST_REACTION);

        tvThresholdGrass.setText(THRESHOLD_GRASS);
        tvThresholdTree.setText(THRESHOLD_TREE);
        tvThresholdMold.setText(THRESHOLD_MOLD);
    }

    // ── Edit buttons ──────────────────────────────────────────────────────────

    private void setupEditButtons(View view) {
        view.findViewById(R.id.btn_edit_info).setOnClickListener(v -> showEditInfoDialog());
        view.findViewById(R.id.btn_add_allergy).setOnClickListener(v -> showAddItemDialog("allergy"));
        view.findViewById(R.id.btn_add_medicine).setOnClickListener(v -> showAddItemDialog("medicine"));
    }

    private void showEditInfoDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_edit_info, null);

        EditText etName  = dialogView.findViewById(R.id.et_edit_name);
        EditText etAge   = dialogView.findViewById(R.id.et_edit_age);
        EditText etCity  = dialogView.findViewById(R.id.et_edit_city);
        EditText etEmail = dialogView.findViewById(R.id.et_edit_email);

        etName.setText(mockName);
        etAge.setText(mockAge);
        etCity.setText(mockCity);
        etEmail.setText(mockEmail);

        new AlertDialog.Builder(requireContext())
                .setTitle("Edit personal info")
                .setView(dialogView)
                .setPositiveButton("Save", (d, w) -> {
                    mockName  = etName.getText().toString().trim();
                    mockAge   = etAge.getText().toString().trim();
                    mockCity  = etCity.getText().toString().trim();
                    mockEmail = etEmail.getText().toString().trim();
                    populateMockData();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddItemDialog(String type) {
        EditText input = new EditText(requireContext());
        input.setHint(type.equals("allergy") ? "e.g. Cat hair" : "e.g. Loratadine 10 mg");

        new AlertDialog.Builder(requireContext())
                .setTitle(type.equals("allergy") ? "Add allergy" : "Add medicine")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    if (type.equals("allergy")) {
                        mockAllergies.add(value);
                        buildAllergyChips();
                    } else {
                        mockMedicines.add(value);
                        buildMedicineRows();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Dynamic lists ─────────────────────────────────────────────────────────

    private void buildAllergyChips() {
        containerAllergies.removeAllViews();
        for (int i = 0; i < mockAllergies.size(); i++) {
            final int idx = i;
            View chip = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_tag_chip, containerAllergies, false);

            TextView label = chip.findViewById(R.id.tv_chip_label);
            ImageButton btnRemove = chip.findViewById(R.id.btn_chip_remove);

            label.setText(mockAllergies.get(idx));
            btnRemove.setOnClickListener(v -> {
                mockAllergies.remove(idx);
                buildAllergyChips();
            });

            containerAllergies.addView(chip);
        }
    }

    private void buildMedicineRows() {
        containerMedicines.removeAllViews();
        for (int i = 0; i < mockMedicines.size(); i++) {
            final int idx = i;
            View row = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_medicine_row, containerMedicines, false);

            TextView label = row.findViewById(R.id.tv_medicine_label);
            ImageButton btnRemove = row.findViewById(R.id.btn_medicine_remove);

            label.setText(mockMedicines.get(idx));
            btnRemove.setOnClickListener(v -> {
                mockMedicines.remove(idx);
                buildMedicineRows();
            });

            containerMedicines.addView(row);
        }
    }
}