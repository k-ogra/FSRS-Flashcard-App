import { useEffect } from "react";
import { useLocation } from "react-router-dom";
import Hero from "./Hero";
import Features from "../header/Features";
import Footer from "../footer/Footer";
import "./Homepage.css";

function Homepage() {
  const location = useLocation();

  useEffect(() => {
    if (location.hash) {
      const el = document.getElementById(location.hash.slice(1));
      if (el) {
        // Small delay to ensure the DOM is rendered
        setTimeout(() => el.scrollIntoView({ behavior: "smooth" }), 0);
      }
    }
  }, [location.hash]);

  return (
    <>
      <Hero />
      <Features />
      <Footer />
    </>
  );
}

export default Homepage;
