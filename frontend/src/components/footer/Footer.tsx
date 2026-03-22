function Footer() {
  // TODO: Link GitHub page
  return (
    <footer className="footer">
      <div className="footer-inner">
        <div className="footer-brand">
          <div className="logo">
            <span className="logo-mark">★</span>
            <span className="logo-text">FSRS Flashcard App</span>
          </div>
        </div>
        <div className="footer-links">
          <div className="footer-col">
            <div className="footer-col-title">Resources</div>
            <ul>
              <li><a href="#">GitHub</a></li>
            </ul>
          </div>
        </div>
      </div>
    </footer>
  );
}

export default Footer;
