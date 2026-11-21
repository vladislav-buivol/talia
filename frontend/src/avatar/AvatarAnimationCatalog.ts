export type AvatarMotion =
  | { kind: "dance" }
  | { kind: "clip" | "pose"; path: string };

export const defaultAnimationPath = "animation/Catwalk_Idle.vrma";

export const animations: readonly { label: string; motion: AvatarMotion; chatId?: string }[] = [
  { label: "Walk", chatId: "walk", motion: { kind: "clip", path: "animation/Standard_Walk.vrma" } },
  { label: "Catwalk idle", motion: { kind: "clip", path: defaultAnimationPath } },
  { label: "Happy idle", motion: { kind: "clip", path: "animation/Male_HappyIdle.vrma" } },
  { label: "Idle", motion: { kind: "clip", path: "animation/Male_Idle.vrma" } },
  { label: "Wave dance", chatId: "wave-dance", motion: { kind: "clip", path: "animation/Male_WaveDance.vrma" } },
  { label: "Happy", chatId: "happy", motion: { kind: "clip", path: "animation/Happy.vrma" } },
  { label: "Bashful", chatId: "bashful", motion: { kind: "clip", path: "animation/Bashful.vrma" } },
  { label: "Backflip", chatId: "backflip", motion: { kind: "clip", path: "animation/Backflip.vrma" } },
  { label: "Run and flip", chatId: "run-and-flip", motion: { kind: "clip", path: "animation/Male_Run2Flip.vrma" } },
];

export const poses: readonly { label: string; motion: AvatarMotion }[] = ["02", "03", "04", "06", "07"].map(number => ({
  label: `Pose ${number}`,
  motion: { kind: "pose", path: `pose/AvatarSample_A-mpc_Male_Pose_${number}.vrma` },
}));

export const originalDance: AvatarMotion = { kind: "dance" };

export function chatAnimation(id: string): AvatarMotion | undefined {
  return id === "dance" ? originalDance : animations.find(animation => animation.chatId === id)?.motion;
}
