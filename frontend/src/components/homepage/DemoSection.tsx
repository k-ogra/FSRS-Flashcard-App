import "./DemoSection.css";

function DemoSection() {
  return (
    <section className="demo" id="demo">
      <div className="section-inner">
        <div className="section-header">
          <span className="section-label">Demo</span>
          <h2 className="section-heading">See it in action</h2>
          <p className="section-sub">
            A quick walkthrough of creating cards, studying with FSRS scheduling, and
            sharing decks.
          </p>
        </div>
        <div className="demo-video-frame">
          <video
            className="demo-video"
            src="/demoVideo.mp4"
            controls
            preload="metadata"
            playsInline
          />
        </div>
      </div>
    </section>
  );
}

export default DemoSection;
