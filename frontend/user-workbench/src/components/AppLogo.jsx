const colors = ['#1A73E8', '#34A853', '#FBBC04', '#EA4335'];

/** Agent Studio 标识：四色 Google 风格星芒。 */
export default function AppLogo({ size = 28 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <path
        d="M16 2c1 4.6 3.4 7 8 8-4.6 1-7 3.4-8 8-1-4.6-3.4-7-8-8 4.6-1 7-3.4 8-8Z"
        fill={colors[0]}
      />
      <path
        d="M25.5 16c.6 2.7 2 4.1 4.7 4.7-2.7.6-4.1 2-4.7 4.7-.6-2.7-2-4.1-4.7-4.7 2.7-.6 4.1-2 4.7-4.7Z"
        fill={colors[2]}
      />
      <path
        d="M7.5 17.5c.8 3.6 2.7 5.5 6.3 6.3-3.6.8-5.5 2.7-6.3 6.3-.8-3.6-2.7-5.5-6.3-6.3 3.6-.8 5.5-2.7 6.3-6.3Z"
        fill={colors[1]}
      />
      <circle cx="24.6" cy="7.4" r="2.4" fill={colors[3]} />
    </svg>
  );
}
