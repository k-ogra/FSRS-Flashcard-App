interface MediaRendererProps {
  url: string | null | undefined;
  fileName: string | null | undefined;
}

const IMAGE_EXTS = [".jpg", ".jpeg", ".png", ".gif", ".webp"];
const AUDIO_EXTS = [".mp3", ".wav", ".ogg"];

function getMediaType(fileName: string): "image" | "audio" | null {
  const lower = fileName.toLowerCase();
  if (IMAGE_EXTS.some((ext) => lower.endsWith(ext))) return "image";
  if (AUDIO_EXTS.some((ext) => lower.endsWith(ext))) return "audio";
  return null;
}

export default function MediaRenderer({ url, fileName }: MediaRendererProps) {
  if (!url || !fileName) return null;

  const type = getMediaType(fileName);
  if (!type) return null;

  if (type === "image") {
    return (
      <img
        src={url}
        alt={fileName}
        className="media-content media-image"
      />
    );
  }

  return (
    <audio controls className="media-content media-audio">
      <source src={url} />
    </audio>
  );
}
