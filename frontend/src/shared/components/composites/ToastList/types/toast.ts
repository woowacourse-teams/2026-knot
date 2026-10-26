import type { ToastIcon, ToastVariant } from "@primitives/ui/Toast";

export interface ShownToast {
  /** 띄울 때마다 늘어나는 번호예요. 같은 토스트를 다시 띄워 시간만 다시 셀 때는 그대로라 나타나는 애니메이션이 다시 재생되지 않아요 */
  id: number;
  variant: ToastVariant;
  message: string;
  /** 종류의 기본 아이콘 대신 그릴 아이콘 */
  icon?: ToastIcon;
  /** 마지막으로 띄운 시각이에요. 바뀌면 떠 있는 시간을 처음부터 다시 세요 */
  shownAt: number;
  /** 화면에 떠 있는 시간이 다 돼 사라지는 애니메이션이 재생되는 중이에요 */
  isLeaving: boolean;
}
