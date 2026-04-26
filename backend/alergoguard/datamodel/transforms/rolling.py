import torch

class RollingMean:
    def __init__(self, window: int = 3):
        self.window = window

    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        """
        x: [T, F]
        returns rolling mean per feature
        """
        T, F = x.shape
        out = torch.zeros_like(x)

        for t in range(T):
            start = max(0, t - self.window + 1)
            out[t] = x[start:t+1].mean(dim=0)

        return out
