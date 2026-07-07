import type { ClipDto } from '../api/types';
import { expiresLabel, lifeFraction } from '../lib/relativeTime';

/**
 * The clip lifeline: a 3px bar whose filled fraction is the clip's remaining
 * life. Carbon while healthy, stamp once ≤ 25% remains.
 */
export function Lifeline({ clip, now }: { clip: ClipDto; now: number }) {
  const fraction = lifeFraction(clip.createdAt, clip.expiresAt, now);
  const fill = `${(fraction * 100).toFixed(1)}%`;
  return (
    <div className="lifeline" role="img" aria-label={expiresLabel(clip.expiresAt, now)}>
      <div
        className={fraction <= 0.25 ? 'lifeline-fill low' : 'lifeline-fill'}
        style={{ width: fill }}
      />
    </div>
  );
}
