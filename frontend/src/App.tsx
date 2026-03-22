import { Routes, Route } from "react-router-dom";
import Navbar from "./components/header/Navbar";
import Homepage from "./components/homepage/Homepage";
import SignupPage from "./components/login/SignupPage";
import LoginPage from "./components/login/LoginPage";
import DecksPage from "./components/decks/DecksPage";
import NotFoundPage from "./components/notfound/NotFoundPage";
import "./App.css";

function App() {
  return (
    <div className="app">
      <Navbar />
      <Routes>
        <Route path="/" element={<Homepage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/decks" element={<DecksPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </div>
  );
}

export default App;
