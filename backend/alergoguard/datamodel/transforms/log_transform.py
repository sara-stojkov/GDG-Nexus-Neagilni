import torch

class LogTransform:
    def __init__(self, eps: float = 1.0):
        """
        eps avoids log(0)
        """
        self.eps = eps

    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        """
        x: pollen concentrations [T, F] or [F]
        """
        return torch.log(x + self.eps)
