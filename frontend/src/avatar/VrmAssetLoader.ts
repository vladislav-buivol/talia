import { VRMLoaderPlugin, VRMUtils, type VRM } from "@pixiv/three-vrm";
import {
  VRMAnimationLoaderPlugin, VRMLookAtQuaternionProxy, createVRMAnimationClip, type VRMAnimation,
} from "@pixiv/three-vrm-animation";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";

export class VrmAssetLoader {
  private readonly loader = new GLTFLoader();

  constructor() {
    this.loader.register(parser => new VRMLoaderPlugin(parser));
    this.loader.register(parser => new VRMAnimationLoaderPlugin(parser));
  }

  async loadAvatar(signal: AbortSignal): Promise<VRM> {
    const gltf = await this.load("talia.vrm", signal);
    const model = gltf.userData.vrm as VRM | undefined;
    if (signal.aborted || !model) {
      VRMUtils.deepDispose(gltf.scene);
      signal.throwIfAborted();
      throw new Error("No avatar found in talia.vrm");
    }
    VRMUtils.rotateVRM0(model);
    if (model.lookAt) {
      const lookAt = new VRMLookAtQuaternionProxy(model.lookAt);
      lookAt.name = "VRMLookAtQuaternionProxy";
      model.scene.add(lookAt);
    }
    return model;
  }

  async loadMotion(path: string, model: VRM, signal: AbortSignal) {
    const gltf = await this.load(path, signal);
    try {
      signal.throwIfAborted();
      const motion = (gltf.userData.vrmAnimations as VRMAnimation[] | undefined)?.[0];
      if (!motion) throw new Error("No VRM Animation found");
      return createVRMAnimationClip(motion, model);
    } finally {
      VRMUtils.deepDispose(gltf.scene);
    }
  }

  private async load(path: string, signal: AbortSignal) {
    const base = new URL(import.meta.env.BASE_URL, location.origin);
    const url = new URL(`models/${path}`, base);
    const response = await fetch(url, { signal });
    if (!response.ok) throw new Error(`Could not load ${path}: ${response.status}`);
    const data = await response.arrayBuffer();
    signal.throwIfAborted();
    return this.loader.parseAsync(data, new URL(".", url).href);
  }
}
