import { isDesktop } from '@/utils/desktopBridge'
import { Monitor } from 'lucide-react'

export function DesktopBadge() {
  if (!isDesktop()) return null
  return (
    <div className="flex items-center gap-1 px-2 py-0.5 rounded text-xs
                    bg-indigo-900/30 text-indigo-400 border border-indigo-800">
      <Monitor size={10} />
      Desktop
    </div>
  )
}
