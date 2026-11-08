import { useSentryRoutes } from "@provider/sentry";
import { routes } from "@routes/routes";
import { Suspense } from "react";

const App = () => {
  const element = useSentryRoutes(routes);

  // 처음 여는 화면의 코드를 받는 동안은 비워 둬요. 화면을 옮길 때는 이동이 transition이라 받는 동안 이전 화면이 남아요
  return <Suspense fallback={null}>{element}</Suspense>;
};

export default App;
