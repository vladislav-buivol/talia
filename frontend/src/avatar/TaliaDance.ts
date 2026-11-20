import type { VRM, VRMHumanBoneName } from "@pixiv/three-vrm";
import { AnimationClip, Euler, Quaternion, QuaternionKeyframeTrack } from "three";

type Angles = readonly [x: number, y: number, z: number];
const beat = 0.46;

// Eight beats in place. Angles are local to the normalized VRM bones.
const choreography: Partial<Record<VRMHumanBoneName, readonly Angles[]>> = {
  hips: [[0, 0, 0], [0, 0.16, 0.08], [0, 0.2, 0.04], [0, -0.15, -0.08],
    [0, -0.22, -0.04], [0, 0.14, 0.08], [0, -0.12, -0.08], [0, 0.06, 0.04], [0, 0, 0]],
  spine: [[0, 0, 0], [0.04, -0.12, -0.11], [-0.04, -0.17, -0.05], [0.05, 0.13, 0.11],
    [-0.03, 0.16, 0.05], [0.05, -0.14, -0.10], [0.03, 0.16, 0.10], [-0.07, 0, 0], [0, 0, 0]],
  head: [[0, 0, 0], [0.13, 0.09, 0.1], [-0.08, 0.12, 0.05], [0.14, -0.07, -0.10],
    [-0.07, -0.12, -0.07], [0.13, 0.11, 0.09], [0.09, -0.12, -0.09], [-0.11, 0.04, 0], [0, 0, 0]],
  leftUpperArm: [[0, 0, -1.12], [0, -0.48, -0.58], [0, -0.18, 0.30], [0, -0.23, -1.11],
    [0, -0.44, -0.78], [0, -0.42, -0.02], [0, -0.42, -0.74], [0, -0.12, 0.52], [0, 0, -1.12]],
  rightUpperArm: [[0, 0, 1.12], [0, 0.23, 1.11], [0, 0.44, 0.78], [0, 0.48, 0.58],
    [0, 0.18, -0.30], [0, 0.42, 0.74], [0, 0.42, 0.02], [0, 0.12, -0.52], [0, 0, 1.12]],
  leftLowerArm: [[0, 0, 0], [0, -0.76, -0.22], [0, -0.54, 0.29], [0, -0.24, 0.05],
    [0, -0.65, 0.16], [0, -0.78, 0.25], [0, -0.55, 0.2], [0, -0.38, 0.32], [0, 0, 0]],
  rightLowerArm: [[0, 0, 0], [0, 0.24, -0.05], [0, 0.65, -0.16], [0, 0.76, 0.22],
    [0, 0.54, -0.29], [0, 0.55, -0.2], [0, 0.78, -0.25], [0, 0.38, -0.32], [0, 0, 0]],
};

export function createTaliaDance(model: VRM) {
  const tracks: QuaternionKeyframeTrack[] = [];
  const rotation = new Quaternion();
  const euler = new Euler();
  for (const [bone, poses] of Object.entries(choreography)) {
    const node = model.humanoid.getNormalizedBoneNode(bone as VRMHumanBoneName);
    if (!node) continue;
    const times = poses.map((_, index) => index * beat);
    const values = poses.flatMap(([x, y, z]) => rotation.setFromEuler(euler.set(x, y, z)).toArray());
    tracks.push(new QuaternionKeyframeTrack(`${node.name}.quaternion`, times, values));
  }
  return new AnimationClip("Talia Dance", -1, tracks);
}
