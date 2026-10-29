import RouteAnnouncer from "@composites/RouteAnnouncer";
import { useSentryRoutes } from "@provider/sentry";
import { routes } from "@routes/routes";

const App = () => {
  const element = useSentryRoutes(routes);

  return (
    <>
      {element}
      <RouteAnnouncer />
    </>
  );
};

export default App;
