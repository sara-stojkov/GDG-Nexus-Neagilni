import torch

class LagFeatures:
    def __init__(self, lag: int = 1):
        self.lag = lag

    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        """
        x: [T, F]
        returns: [T, F] with lag shift (previous exposure effect)
        """
        T, F = x.shape
        out = torch.zeros_like(x)

        out[self.lag:] = x[:-self.lag]
        return out
