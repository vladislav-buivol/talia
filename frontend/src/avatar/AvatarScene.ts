import { Box3, DirectionalLight, HemisphereLight, MathUtils, PerspectiveCamera, Scene, Vector3, WebGLRenderer, type Object3D } from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import type { VRM } from "@pixiv/three-vrm";

type ViewFrame = { center: Vector3; height: number };

export class AvatarScene {
  private readonly scene = new Scene();
  private readonly camera = new PerspectiveCamera(30, 1, 0.01, 40);
  private readonly renderer: WebGLRenderer;
  private readonly orbit: OrbitControls;
  private readonly observer: ResizeObserver;
  private readonly view: Window;
  private frame = 0;
  private portrait?: ViewFrame;
  private fullBody?: ViewFrame;
  private hips?: Object3D;
  private readonly hipPosition = new Vector3();
  private readonly previousHipPosition = new Vector3();
  private readonly motionOffset = new Vector3();

  constructor(canvas: HTMLCanvasElement, private readonly viewport: HTMLElement) {
    this.view = canvas.ownerDocument.defaultView ?? window;
    this.renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true });
    this.renderer.setPixelRatio(Math.min(this.view.devicePixelRatio, 1.5));

    const key = new DirectionalLight(0xfff5e7, 2);
    key.position.set(1, 2, 3);
    this.scene.add(key, new HemisphereLight(0xf9fcff, 0x8b8e83, 1.4));

    this.orbit = new OrbitControls(this.camera, canvas);
    this.orbit.enableDamping = true;
    this.orbit.minDistance = 0.3;
    this.orbit.maxDistance = 20;
    this.orbit.screenSpacePanning = true;
    this.orbit.zoomToCursor = true;
    this.observer = new ResizeObserver(() => this.resize());
    this.observer.observe(viewport);
    this.resize();
  }

  attach(model: VRM) {
    this.scene.add(model.scene);
    model.scene.updateMatrixWorld(true);
    const bounds = new Box3().setFromObject(model.scene, true);
    const center = bounds.getCenter(new Vector3());
    const height = Math.max(bounds.max.y - bounds.min.y, 0.7);
    this.fullBody = { center, height: height * 1.65 };
    const head = model.humanoid.getNormalizedBoneNode("head");
    const hips = model.humanoid.getNormalizedBoneNode("hips");
    if (hips) {
      this.hips = hips;
      hips.getWorldPosition(this.previousHipPosition);
    }
    if (head && hips) {
      const headPosition = head.getWorldPosition(new Vector3());
      const hipPosition = hips.getWorldPosition(new Vector3());
      const torso = Math.max(headPosition.y - hipPosition.y, 0.35);
      this.portrait = { center: headPosition.lerp(hipPosition, 0.38), height: torso * 1.55 };
    }
    this.resetView(false);
  }

  resetView(fullBody: boolean) {
    const frame = fullBody ? this.fullBody : (this.portrait ?? this.fullBody);
    if (!frame) return;
    // Clear residual damping and pan offsets before restoring the camera.
    this.orbit.enableDamping = false;
    this.orbit.update();
    this.orbit.target.copy(frame.center).add(this.motionOffset);
    const height = Math.max(frame.height, frame.height * 0.72 / this.camera.aspect);
    const distance = height / (2 * Math.tan(MathUtils.degToRad(this.camera.fov / 2)));
    this.camera.position.copy(this.orbit.target).add(new Vector3(0, 0, distance));
    this.orbit.update();
    this.orbit.enableDamping = true;
  }

  start(update: (now: number) => void) {
    const render = (now: number) => {
      update(now);
      this.trackHorizontalMovement();
      this.orbit.update();
      this.renderer.render(this.scene, this.camera);
      this.frame = this.view.requestAnimationFrame(render);
    };
    this.frame = this.view.requestAnimationFrame(render);
  }

  private trackHorizontalMovement() {
    if (!this.hips) return;
    this.hips.getWorldPosition(this.hipPosition);
    const x = this.hipPosition.x - this.previousHipPosition.x;
    const z = this.hipPosition.z - this.previousHipPosition.z;
    this.previousHipPosition.copy(this.hipPosition);
    this.motionOffset.x += x;
    this.motionOffset.z += z;
    this.camera.position.x += x;
    this.camera.position.z += z;
    this.orbit.target.x += x;
    this.orbit.target.z += z;
  }

  dispose() {
    this.observer.disconnect();
    this.view.cancelAnimationFrame(this.frame);
    this.orbit.dispose();
    this.renderer.dispose();
  }

  private resize() {
    const width = Math.max(this.viewport.clientWidth, 1);
    const height = Math.max(this.viewport.clientHeight, 1);
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(width, height, false);
  }
}
