import { Controller } from "/scripts/vendor/stimulus.js";

export default class extends Controller {
  static targets = ["modal", "step", "dot"];

  static values = {
    dismissUrl:  String,
    csrf:        String,
    totalSteps:  { type: Number,  default: 5 },
    autoshow:    { type: Boolean, default: false }
  };

  connect() {
    this.currentStep = 1;
    this._updateUI();

    // Listen for show requests from anywhere on the page (e.g. home "How it works" button)
    this._showHandler = () => this.show();
    window.addEventListener("onboarding:show", this._showHandler);

    // Auto-show on first visit — guarded by sessionStorage so Turbo navigations
    // don't re-trigger it on every link click.
    if (this.autoshowValue && !sessionStorage.getItem("onboarding:autoshow-seen")) {
      sessionStorage.setItem("onboarding:autoshow-seen", "1");
      this.show();
    }
  }

  disconnect() {
    window.removeEventListener("onboarding:show", this._showHandler);
  }

  // ── Public API ──────────────────────────────────────────────────────────────
  show() {
    this.currentStep = 1;
    this._updateUI();
    const modal = this.modalTarget;
    modal.classList.remove("onboarding--exiting");
    modal.style.display = "flex";
    document.body.style.overflow = "hidden";
  }

  next() {
    if (this.currentStep < this.totalStepsValue) {
      this.currentStep++;
      this._updateUI();
    } else {
      this.finish();
    }
  }

  prev() {
    if (this.currentStep > 1) {
      this.currentStep--;
      this._updateUI();
    }
  }

  goTo(e) {
    const step = parseInt(e.currentTarget.dataset.step);
    if (step >= 1 && step <= this.totalStepsValue) {
      this.currentStep = step;
      this._updateUI();
    }
  }

  async finish() {
    this._dismiss();
    await this._markComplete();
  }

  async skip() {
    this._dismiss();
    await this._markComplete();
  }

  // ── Private ─────────────────────────────────────────────────────────────────
  _updateUI() {
    this.stepTargets.forEach((el, i) => {
      const active = i + 1 === this.currentStep;
      el.hidden = !active;
      el.setAttribute("aria-hidden", active ? "false" : "true");
    });

    this.dotTargets.forEach((dot, i) => {
      dot.classList.toggle("onboarding__dot--active", i + 1 === this.currentStep);
      dot.classList.toggle("onboarding__dot--done",   i + 1 < this.currentStep);
    });
  }

  _dismiss() {
    const modal = this.modalTarget;
    modal.classList.add("onboarding--exiting");
    setTimeout(() => { modal.style.display = "none"; modal.classList.remove("onboarding--exiting"); }, 350);
    document.body.style.overflow = "";
  }

  async _markComplete() {
    try {
      await fetch(this.dismissUrlValue, {
        method: "POST",
        headers: {
          "X-CSRF-Token": this.csrfValue,
          "X-Requested-With": "XMLHttpRequest",
          "Content-Type": "application/json"
        }
      });
    } catch { /* non-critical */ }
  }
}
