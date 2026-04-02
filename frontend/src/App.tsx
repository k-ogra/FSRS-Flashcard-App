import { Routes, Route } from "react-router-dom";
import Navbar from "./components/header/Navbar";
import Homepage from "./components/homepage/Homepage";
import SignupPage from "./components/login/SignupPage";
import LoginPage from "./components/login/LoginPage";
import DecksPage from "./components/decks/DecksPage";
import SharedDecksPage from "./components/decks/SharedDecksPage";
import PublicDecksPage from "./components/decks/PublicDecksPage";
import StudyPage from "./components/study/StudyPage";
import SettingsPage from "./components/settings/SettingsPage";
import PreviewPage from "./components/preview/PreviewPage";
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
        <Route path="/my-decks" element={<DecksPage />} />
        <Route path="/my-decks/:id/study" element={<StudyPage />} />
        <Route path="/decks/:id/preview" element={<PreviewPage />} />
        <Route path="/shared-decks" element={<SharedDecksPage />} />
        <Route path="/public-decks" element={<PublicDecksPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </div>
  );
}

export default App;
