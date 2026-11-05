import { useQuery } from "@tanstack/react-query";

import type { MeetingInsightsDataset } from "../types/dataset";

/** 개발 서버가 git에 들어가지 않는 실제 데이터 파일을 내주는 주소. `webpack.config.js`에 있어요 */
export const LOCAL_DATASET_PATH = "/__dev/meeting-insights";

/** 실제 데이터 파일이 없으면 저장소의 가상 예시를 써요. 팀원이 브랜치만 받아도 화면을 볼 수 있어요 */
const loadDataset = async (): Promise<MeetingInsightsDataset> => {
  const response = await fetch(LOCAL_DATASET_PATH);
  const isJson = response.headers
    .get("content-type")
    ?.includes("application/json");

  if (response.ok && isJson === true) return response.json();

  const { exampleMeetingInsightsDataset } =
    await import("@api/mock/responses/meetingInsights");

  return exampleMeetingInsightsDataset;
};

/** 페이지가 그릴 회의 데이터를 받아요. 시안이라 파일 하나를 통째로 읽어요 */
export const useMeetingInsightsDataset = () => {
  const { data: dataset, isPending } = useQuery({
    queryKey: ["meetingInsights", "dataset"],
    queryFn: loadDataset,
    staleTime: Infinity,
  });

  return { dataset, isPending };
};
