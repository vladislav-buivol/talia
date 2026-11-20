export type AvatarMotion =
  | { kind: "dance" }
  | { kind: "clip" | "pose"; path: string };

export const defaultAnimationPath = "animation/Catwalk_Idle.vrma";

export const animations: readonly { label: string; motion: AvatarMotion }[] = [
  { label: "Walk", motion: { kind: "clip", path: "animation/Standard_Walk.vrma" } },
  { label: "Catwalk idle", motion: { kind: "clip", path: defaultAnimationPath } },
  { label: "Happy idle", motion: { kind: "clip", path: "animation/Male_HappyIdle.vrma" } },
  { label: "Idle", motion: { kind: "clip", path: "animation/Male_Idle.vrma" } },
  { label: "Wave dance", motion: { kind: "clip", path: "animation/Male_WaveDance.vrma" } },
  { label: "Happy", motion: { kind: "clip", path: "animation/Happy.vrma" } },
  { label: "Bashful", motion: { kind: "clip", path: "animation/Bashful.vrma" } },
  { label: "Backflip", motion: { kind: "clip", path: "animation/Backflip.vrma" } },
  { label: "Run and flip", motion: { kind: "clip", path: "animation/Male_Run2Flip.vrma" } },
];

export const poses: readonly { label: string; motion: AvatarMotion }[] = ["02", "03", "04", "06", "07"].map(number => ({
  label: `Pose ${number}`,
  motion: { kind: "pose", path: `pose/AvatarSample_A-mpc_Male_Pose_${number}.vrma` },
}));

export const originalDance: AvatarMotion = { kind: "dance" };
