export const feedbackMutationKeys = {
  all: ["feedback"] as const,
  send: () => [...feedbackMutationKeys.all, "send"] as const,
};
