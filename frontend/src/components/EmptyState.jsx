export default function EmptyState({
  title = "All caught up!",
  description = "No pending requests. Great job! All incoming vendor log streams are mapped and healthy.",
  actionButton = null,
  transparent = false
}) {
  return (
    <div className={`w-full py-12 px-6 text-center flex flex-col items-center justify-center ${
      transparent ? "my-2" : "bg-white rounded-3xl border border-slate-200/80 shadow-sm my-6"
    }`}>
      {/* Official ULPF Empty Page Character Illustration SVG */}
      <div className="w-52 h-40 mb-4 flex items-center justify-center">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 180" fill="none" className="w-full h-full">
          <defs>
            <linearGradient id="basketGrad" x1="60" y1="70" x2="180" y2="150" gradientUnits="userSpaceOnUse">
              <stop stopColor="#14B8A6" />
              <stop offset="1" stopColor="#0D9488" />
            </linearGradient>
            <filter id="softGlow" x="-20%" y="-20%" width="140%" height="140%">
              <feDropShadow dx="0" dy="8" stdDeviation="12" floodColor="#14B8A6" floodOpacity="0.18" />
            </filter>
          </defs>
          {/* Background gentle aura */}
          <circle cx="120" cy="95" r="70" fill="#F0FDFA" />
          <circle cx="120" cy="95" r="50" fill="#CCFBF1" opacity="0.6" />
          {/* Sparkles / decorative dots */}
          <circle cx="50" cy="45" r="4" fill="#FCD34D" />
          <circle cx="190" cy="55" r="5" fill="#A78BFA" />
          <circle cx="65" cy="130" r="3" fill="#6EE7B7" />
          <circle cx="180" cy="125" r="4" fill="#F472B6" />
          <path d="M175 40L177 34L183 36L178 41L180 47L174 44L169 48L171 42L166 38L172 37Z" fill="#FBBF24" opacity="0.8" />
          {/* Friendly Basket Character */}
          <path d="M72 82C72 75 77.6 69.4 84.6 69.4H155.4C162.4 69.4 168 75 168 82L160 132C159 138.6 153.4 143.6 146.7 143.6H93.3C86.6 143.6 81 138.6 80 132L72 82Z" fill="url(#basketGrad)" filter="url(#softGlow)" />
          {/* Basket Rim */}
          <rect x="66" y="65" width="108" height="14" rx="7" fill="#2DD4BF" />
          {/* Smiling Face details */}
          <path d="M100 102C101.5 98 106.5 98 108 102" stroke="white" strokeWidth="3" strokeLinecap="round" />
          <path d="M132 102C133.5 98 138.5 98 140 102" stroke="white" strokeWidth="3" strokeLinecap="round" />
          <circle cx="95" cy="108" r="4.5" fill="#FDA4AF" opacity="0.8" />
          <circle cx="145" cy="108" r="4.5" fill="#FDA4AF" opacity="0.8" />
          <path d="M112 112C115 117 125 117 128 112" stroke="white" strokeWidth="3.5" strokeLinecap="round" />
          <path d="M152 60L158 56L154 56L158 52" stroke="#0D9488" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" opacity="0.5" />
        </svg>
      </div>

      <h3 className="text-lg font-bold text-slate-900 mb-1 tracking-tight">
        {title}
      </h3>
      <p className="text-slate-500 text-xs sm:text-sm max-w-md mx-auto leading-relaxed mb-4">
        {description}
      </p>

      {actionButton && <div className="mt-2">{actionButton}</div>}
    </div>
  );
}
