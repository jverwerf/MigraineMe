// Head-diagram coordinates and label maps, mirroring PainLocationScreen.kt /
// PainLocationData.swift. Positions are fractions of the head image, so the
// printed heat dots land exactly where the app draws them.

export type Point = { x: number; y: number; view: "front" | "back" | "both"; label: string };

export const PAIN_POINTS: Record<string, Point> = {
  vertex:            { x: 0.496, y: 0.190, view: "both",  label: "Top of Head" },
  forehead_center:   { x: 0.499, y: 0.257, view: "front", label: "Forehead Center" },
  forehead_left:     { x: 0.341, y: 0.257, view: "front", label: "Forehead Left" },
  forehead_right:    { x: 0.654, y: 0.253, view: "front", label: "Forehead Right" },
  brow_left:         { x: 0.386, y: 0.312, view: "front", label: "Left Brow" },
  brow_right:        { x: 0.624, y: 0.315, view: "front", label: "Right Brow" },
  temple_left:       { x: 0.241, y: 0.332, view: "front", label: "Left Temple" },
  temple_right:      { x: 0.756, y: 0.338, view: "front", label: "Right Temple" },
  eye_left:          { x: 0.354, y: 0.365, view: "front", label: "Left Eye" },
  eye_right:         { x: 0.656, y: 0.365, view: "front", label: "Right Eye" },
  ear_left:          { x: 0.249, y: 0.402, view: "front", label: "Left Ear" },
  ear_right:         { x: 0.744, y: 0.402, view: "front", label: "Right Ear" },
  nose_bridge:       { x: 0.496, y: 0.360, view: "front", label: "Nose Bridge" },
  sinus_left:        { x: 0.391, y: 0.410, view: "front", label: "Left Sinus" },
  sinus_right:       { x: 0.609, y: 0.410, view: "front", label: "Right Sinus" },
  jaw_left:          { x: 0.324, y: 0.488, view: "front", label: "Left Jaw / TMJ" },
  jaw_right:         { x: 0.680, y: 0.492, view: "front", label: "Right Jaw / TMJ" },
  teeth_left:        { x: 0.448, y: 0.492, view: "front", label: "Teeth Left" },
  teeth_right:       { x: 0.555, y: 0.492, view: "front", label: "Teeth Right" },
  neck_left:         { x: 0.365, y: 0.595, view: "both",  label: "Neck Left" },
  neck_right:        { x: 0.637, y: 0.598, view: "both",  label: "Neck Right" },

  top_of_back_head_left:  { x: 0.345, y: 0.222, view: "back", label: "Top of Back Head Left" },
  top_of_back_head_right: { x: 0.651, y: 0.222, view: "back", label: "Top of Back Head Right" },
  occipital_center:  { x: 0.500, y: 0.285, view: "back", label: "Occipital Center" },
  behind_ear_left:   { x: 0.298, y: 0.339, view: "back", label: "Behind Left Ear" },
  behind_ear_right:  { x: 0.712, y: 0.339, view: "back", label: "Behind Right Ear" },
  base_skull_left:   { x: 0.383, y: 0.420, view: "back", label: "Base of Skull Left" },
  base_skull_center: { x: 0.500, y: 0.405, view: "back", label: "Base of Skull Center" },
  base_skull_right:  { x: 0.627, y: 0.420, view: "back", label: "Base of Skull Right" },
  upper_back_left:   { x: 0.275, y: 0.613, view: "back", label: "Upper Back Left" },
  upper_back_right:  { x: 0.725, y: 0.613, view: "back", label: "Upper Back Right" },
  upper_back_center: { x: 0.500, y: 0.662, view: "back", label: "Upper Back Center" },
  shoulder_left:     { x: 0.090, y: 0.667, view: "back", label: "Left Shoulder" },
  shoulder_right:    { x: 0.900, y: 0.667, view: "back", label: "Right Shoulder" },
  center_back:       { x: 0.500, y: 0.780, view: "back", label: "Center / Lower Back" },
};

export const PAIN_LABELS: Record<string, string> = Object.fromEntries(
  Object.entries(PAIN_POINTS).map(([id, p]) => [id, p.label]),
);

// Aura zone ids are `{eye}_{row}_{col}` — the 3x3 grid per eye that both the
// log sheet and the Insights heat-map use (AuraDetailSheet.kt).
export const AURA_ROWS = ["top", "center", "bottom"] as const;
export const AURA_COLS = ["left", "center", "right"] as const;

const CELL_LABEL: Record<string, string> = {
  top_left: "top left", top_center: "top center", top_right: "top right",
  center_left: "center left", center_center: "center", center_right: "center right",
  bottom_left: "bottom left", bottom_center: "bottom center", bottom_right: "bottom right",
};

export function AURA_ZONE_LABEL(id: string): string {
  const [eye, ...rest] = id.split("_");
  const cell = rest.join("_");
  const eyeLabel = eye === "left" ? "Left eye" : eye === "right" ? "Right eye" : eye;
  return CELL_LABEL[cell] ? `${eyeLabel} · ${CELL_LABEL[cell]}` : id;
}
