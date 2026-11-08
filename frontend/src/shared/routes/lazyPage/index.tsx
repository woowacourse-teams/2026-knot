import {
  isValidElement,
  lazy,
  type ComponentType,
  type ReactNode,
} from "react";
import { matchRoutes, type Location, type RouteObject } from "react-router";

interface PageModule {
  default: ComponentType;
}

/**
 * 페이지 코드를 첫 화면 번들에서 빼고, 그 화면을 처음 그릴 때 받아요.
 *
 * 받는 동안에는 가장 가까운 `Suspense`의 대체 화면이 보이고, 화면 이동 중이면 이전 화면이 그대로 남아요.
 * `preload`로 미리 받아 둔 페이지는 기다리지 않고 바로 그려요. 받지 못하면 그 오류를 던져 `AppErrorBoundary`가 안내해요.
 */
export default function lazyPage(load: () => Promise<PageModule>) {
  let request: Promise<PageModule> | undefined;
  let loaded: PageModule | undefined;

  const preload = () =>
    (request ??= load().then(
      (module) => (loaded = module),
      (error: unknown) => {
        // 실패한 요청을 남겨 두면 다시 시도해도 같은 실패만 돌려줘서 비워요
        request = undefined;
        throw error;
      },
    ));

  const LoadingPage = lazy(preload);

  // lazy는 이미 받은 코드도 처음 한 번은 기다려 대체 화면이 잠깐 보여서, 받아 둔 페이지는 바로 그려요
  function LazyPage() {
    const Page = loaded?.default ?? LoadingPage;

    return <Page />;
  }

  return Object.assign(LazyPage, { preload });
}

interface Preloadable {
  preload?: () => unknown;
}

/** 요소와 그 요소가 props로 받은 요소 중 `lazyPage` 페이지의 코드를 받아요. 레이아웃이나 가드가 페이지를 감싸도 찾아요 */
const preloadElement = (node: unknown) => {
  if (!isValidElement<Record<string, ReactNode>>(node)) return;

  void (node.type as Preloadable).preload?.();
  Object.values(node.props).forEach(preloadElement);
};

/**
 * 주소에 맞는 라우트의 페이지 코드를 미리 받아요.
 *
 * 가드와 레이아웃은 로그인과 워크스페이스를 확인한 뒤에야 페이지를 그려서, 그때 받기 시작하면 확인 응답과 코드 받기가 차례로 이어져요.
 * 앱을 시작할 때 불러 두 요청이 함께 가게 해요.
 */
export const preloadPages = (
  routes: RouteObject[],
  location: Partial<Location>,
) => {
  matchRoutes(routes, location)?.forEach(({ route }) =>
    preloadElement(route.element),
  );
};
